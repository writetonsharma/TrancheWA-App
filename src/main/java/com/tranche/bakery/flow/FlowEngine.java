package com.tranche.bakery.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlowEngine {

    private static final Set<String> SUPPORTED_TYPES = Set.of("text", "interactive", "image", "location");

    // Matches a message that starts with the word "hi" (case-insensitive), optionally
    // followed by more text — e.g. "hi", "Hi there", "Hi TRANCHÉ, I want to order the milk buns".
    // The word boundary prevents false matches like "high" or "Hitech City".
    private static final Pattern GREETING_PATTERN =
            Pattern.compile("hi\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Legacy website "Message us" deep-links (and older saved links) prefill this exact
    // phrase. It routes the customer to the contact handoff instead of the order menu.
    private static final Pattern CONTACT_PATTERN =
            Pattern.compile("i have a question for the bakery\\.?", Pattern.CASE_INSENSITIVE);

    // States where the customer has no in-progress order, so the contact shortcut may safely take over.
    private static final Set<String> RESTING_STATES = Set.of("IDLE", "MAIN_MENU");

    // Appended when a mid-order customer types something unexpected, so the way to reach
    // a person (or restart) is always one tap away without disrupting the current step.
    private static final String HELP_HINT =
            "\n\n_Have a question? Send *hi*, then tap *Message Us* to reach a person._";

    private final FlowLoader flowLoader;
    private final DataSourceResolver dataSourceResolver;
    private final ConversationRepository conversationRepository;
    private final WhatsAppClient whatsAppClient;
    private final OrderService orderService;
    private final List<FlowAction> actions;

    @Transactional
    public void handle(Customer customer, WhatsappConversation conversation,
                       String messageType, String input, JsonNode rawMessage) {

        String phone = customer.getPhone();

        if (!SUPPORTED_TYPES.contains(messageType)) {
            log.debug("Unsupported message type={} from {}", messageType, phone);
            whatsAppClient.sendText(phone,
                    "Please send a text message or use the available buttons.\nSend *hi* to return to the main menu.");
            return;
        }

        // Global greeting — "hi" (optionally followed by more text) cancels draft, resets conversation
        if (GREETING_PATTERN.matcher(input.trim()).matches()) {
            orderService.cancelDraftIfExists(customer);
            conversation.setContext(new HashMap<>());
            // First-time customers must select their delivery area before anything else
            String nextState = (customer.getDeliveryArea() == null) ? "AREA_SELECT" : "MAIN_MENU";
            enterState(customer, conversation, nextState, input, messageType, rawMessage);
            return;
        }

        // Global contact shortcut — catches legacy/saved website "Message us" links and hands
        // the customer the human contact number (MESSAGE_CONTACT) instead of the order menu.
        // Only fires from a resting point (idle / main menu); if the customer is mid-order,
        // the phrase falls through to normal handling so their in-progress order is never wiped.
        if (CONTACT_PATTERN.matcher(input.trim()).matches() && isAtRestingPoint(conversation)) {
            conversation.setContext(new HashMap<>());
            enterState(customer, conversation, "MESSAGE_CONTACT", input, messageType, rawMessage);
            return;
        }

        // Global cancel_<id> — cancels a specific order regardless of conversation state
        if (input.trim().matches("cancel_\\d+")) {
            long targetId = Long.parseLong(input.trim().substring("cancel_".length()));
            boolean cancelled = orderService.cancelByIdForCustomer(targetId, customer.getId());
            if (cancelled) {
                conversation.setContext(new HashMap<>());
                conversationRepository.save(conversation);
                whatsAppClient.sendText(phone,
                        "✅ Order cancelled. Send *hi* whenever you'd like to place a new order. 🥖");
                enterState(customer, conversation, "IDLE", input, messageType, rawMessage);
            } else {
                whatsAppClient.sendText(phone,
                        "This order can no longer be cancelled — it may have already been confirmed or delivered. " +
                        "Send *hi* to return to the main menu.");
            }
            return;
        }

        // Global paynow_<id> ? re-surface the QR for a specific unpaid order, regardless of
        // conversation state. Lets a customer who dismissed the QR pay from the order-status
        // screen without hunting for the old image.
        if (input.trim().matches("paynow_\\d+")) {
            long targetId = Long.parseLong(input.trim().substring("paynow_".length()));
            Order payable = orderService.findPayableForCustomer(targetId, customer.getId()).orElse(null);
            if (payable != null) {
                Map<String, Object> payCtx = new HashMap<>();
                payCtx.put("orderId", payable.getId().toString());
                conversation.setContext(payCtx);
                enterState(customer, conversation, "PAYMENT_PENDING", input, messageType, rawMessage);
            } else {
                whatsAppClient.sendText(phone,
                        "This order can no longer be paid \u2014 it may already be confirmed or cancelled. " +
                        "Send *hi* to return to the main menu.");
            }
            return;
        }

        // Global late-payment recovery: an image arrives but there is no active order awaiting
        // payment. This happens when the customer's order was cancelled at the 5 PM cutoff yet they
        // still pay against the old QR afterwards. Offer to revive that order on a valid bake day.
        if ("image".equals(messageType) && !orderService.hasPendingPayment(customer.getId())) {
            Order revivable = orderService.findRevivableLatePayment(customer.getId()).orElse(null);
            if (revivable != null) {
                String mediaId = rawMessage.path("image").path("id").asText(null);
                if (mediaId != null) {
                    Map<String, Object> recoveryCtx = new HashMap<>();
                    recoveryCtx.put("orderId", revivable.getId().toString());
                    recoveryCtx.put("lateScreenshotMediaId", mediaId);
                    conversation.setContext(recoveryCtx);
                    enterState(customer, conversation, "LATE_PAYMENT_DATE", input, messageType, rawMessage);
                    return;
                }
            }
        }

        String currentStateName = conversation.getState();
        StateConfig stateConfig = flowLoader.getState(currentStateName);

        if (stateConfig == null) {
            log.warn("Unknown state '{}' for customer {}, resetting", currentStateName, phone);
            enterState(customer, conversation, "MAIN_MENU", input, messageType, rawMessage);
            return;
        }

        TransitionConfig transition = findTransition(stateConfig, input, messageType);

        if (transition == null) {
            if (stateConfig.getDefaultResponse() != null) {
                whatsAppClient.sendText(phone, stateConfig.getDefaultResponse() + helpHintFor(conversation));
            }
            resendCurrentState(phone, customer, stateConfig, conversation);
            return;
        }

        // Error transition — send message and stay in current state
        if (transition.getErrorMessage() != null) {
            whatsAppClient.sendText(phone, transition.getErrorMessage() + helpHintFor(conversation));
            resendCurrentState(phone, customer, stateConfig, conversation);
            return;
        }

        // Save context key from input
        if (transition.getSaveContext() != null) {
            Map<String, Object> ctx = conversation.getContext() != null
                    ? new HashMap<>(conversation.getContext()) : new HashMap<>();
            ctx.put(transition.getSaveContext(), input);
            conversation.setContext(ctx);
        }

        ActionContext actionCtx = buildActionContext(customer, conversation, input, messageType, rawMessage);

        String nextStateName = transition.getNextState();

        // Execute transition action; allow it to override the destination state
        if (transition.getAction() != null) {
            executeAction(transition.getAction(), actionCtx);
            if (actionCtx.getRedirectState() != null) {
                nextStateName = actionCtx.getRedirectState();
            }
        }

        enterState(customer, conversation, nextStateName, input, messageType, rawMessage);
    }

    private boolean isAtRestingPoint(WhatsappConversation conversation) {
        String state = conversation.getState();
        return state == null || RESTING_STATES.contains(state);
    }

    // Only nudges customers who are mid-order; at a resting point (IDLE/MAIN_MENU)
    // the "Message Us" option is already on screen, so the hint would be noise.
    private String helpHintFor(WhatsappConversation conversation) {
        return isAtRestingPoint(conversation) ? "" : HELP_HINT;
    }

    /**
     * Transitions conversation to the given state, runs its entry action (with redirect support),
     * sends its entry message, and follows any auto-transition.
     */
    private void enterState(Customer customer, WhatsappConversation conversation,
                            String stateName, String input, String messageType, JsonNode rawMessage) {
        conversation.setState(stateName);
        conversationRepository.save(conversation);

        StateConfig state = flowLoader.getState(stateName);
        if (state == null) return;

        String phone = customer.getPhone();
        ActionContext ctx = buildActionContext(customer, conversation, input, messageType, rawMessage);

        if (state.getEntryAction() != null) {
            executeAction(state.getEntryAction(), ctx);
            if (ctx.getRedirectState() != null) {
                enterState(customer, conversation, ctx.getRedirectState(), input, messageType, rawMessage);
                return;
            }
        }

        if (state.getEntryMessage() != null) {
            sendEntryMessage(phone, state, conversation.getContext());
        }

        if (state.getAutoTransition() != null) {
            enterState(customer, conversation, state.getAutoTransition(), input, messageType, rawMessage);
        }
    }

    private void resendCurrentState(String phone, Customer customer,
                                    StateConfig stateConfig, WhatsappConversation conversation) {
        if (stateConfig.getEntryAction() != null) {
            executeAction(stateConfig.getEntryAction(),
                    buildActionContext(customer, conversation, "", "", null));
        } else if (stateConfig.getEntryMessage() != null) {
            sendEntryMessage(phone, stateConfig, conversation.getContext());
        }
    }

    private void sendEntryMessage(String phone, StateConfig state, Map<String, Object> context) {
        MessageConfig msg = state.getEntryMessage();
        switch (msg.getType()) {
            case "text" -> whatsAppClient.sendText(phone, msg.getBody());
            case "buttons" -> whatsAppClient.sendButtons(phone, msg.getBody(),
                    msg.getButtons().stream()
                            .map(b -> new WhatsAppMessage.Button(b.getId(), b.getTitle()))
                            .toList());
            case "list" -> {
                List<WhatsAppMessage.Section> sections = msg.getDataSource() != null
                        ? dataSourceResolver.resolve(msg.getDataSource(), context)
                        : List.of();
                if (sections.isEmpty() || sections.stream().allMatch(s -> s.getRows().isEmpty())) {
                    whatsAppClient.sendText(phone,
                            "Nothing is available right now. Send *hi* to return to the main menu.");
                    return;
                }
                whatsAppClient.sendList(phone, msg.getBody(), msg.getButtonLabel(), sections);
            }
        }
    }

    private TransitionConfig findTransition(StateConfig state, String input, String messageType) {
        if (state.getTransitions() == null) return null;

        for (TransitionConfig t : state.getTransitions()) {
            if (t.getMatchType() != null) {
                if (t.getMatchType().equalsIgnoreCase(messageType)) return t;
                continue;
            }
            if ("*".equals(t.getMatch())) return t;
            String candidate = t.isIgnoreCase() ? input.trim().toLowerCase() : input.trim();
            String pattern   = t.isIgnoreCase() ? t.getMatch().toLowerCase() : t.getMatch();
            if (candidate.equals(pattern)) return t;
        }
        return null;
    }

    private void executeAction(String actionName, ActionContext ctx) {
        actions.stream()
                .filter(a -> a.getName().equals(actionName))
                .findFirst()
                .ifPresentOrElse(
                        a -> {
                            try {
                                a.execute(ctx);
                            } catch (Exception e) {
                                log.error("Action '{}' threw an exception: {}", actionName, e.getMessage(), e);
                            }
                        },
                        () -> log.warn("No action registered for: {}", actionName));
    }

    private ActionContext buildActionContext(Customer customer, WhatsappConversation conversation,
                                             String input, String messageType, JsonNode rawMessage) {
        return ActionContext.builder()
                .customer(customer)
                .conversation(conversation)
                .input(input)
                .messageType(messageType)
                .rawMessage(rawMessage)
                .build();
    }
}
