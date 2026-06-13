package com.tranche.bakery.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.conversation.ConversationRepository;
import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlowEngine {

    private static final Set<String> SUPPORTED_TYPES = Set.of("text", "interactive", "image", "location");

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
                    "I can only understand text messages and button selections.\nSend *hi* to start or continue your order.");
            return;
        }

        // Global "hi" — cancel draft, reset conversation
        if ("hi".equalsIgnoreCase(input.trim())) {
            orderService.cancelDraftIfExists(customer);
            conversation.setContext(new HashMap<>());
            // First-time customers must select their delivery area before anything else
            String nextState = (customer.getDeliveryArea() == null) ? "AREA_SELECT" : "MAIN_MENU";
            enterState(customer, conversation, nextState, input, messageType, rawMessage);
            return;
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
                whatsAppClient.sendText(phone, stateConfig.getDefaultResponse());
            }
            resendCurrentState(phone, customer, stateConfig, conversation);
            return;
        }

        // Error transition — send message and stay in current state
        if (transition.getErrorMessage() != null) {
            whatsAppClient.sendText(phone, transition.getErrorMessage());
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
                            "Sorry, nothing is available right now. Send *hi* to start over.");
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
                        a -> a.execute(ctx),
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
