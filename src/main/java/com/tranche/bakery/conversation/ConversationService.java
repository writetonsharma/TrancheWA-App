package com.tranche.bakery.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.flow.FlowEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final FlowEngine flowEngine;

    @Transactional
    public void handle(Customer customer, String messageType, JsonNode message) {
        WhatsappConversation conversation = conversationRepository
                .findTopByCustomerOrderByStartedAtDesc(customer)
                .orElseGet(() -> createConversation(customer));

        String input = extractInput(messageType, message);
        log.debug("customer={} state={} type={} input={}", customer.getPhone(), conversation.getState(), messageType, input);

        flowEngine.handle(customer, conversation, messageType, input, message);
    }

    private String extractInput(String type, JsonNode message) {
        return switch (type) {
            case "text" -> message.path("text").path("body").asText("").trim();
            case "interactive" -> {
                JsonNode interactive = message.path("interactive");
                yield switch (interactive.path("type").asText()) {
                    case "button_reply" -> interactive.path("button_reply").path("id").asText("");
                    case "list_reply"   -> interactive.path("list_reply").path("id").asText("");
                    default -> "";
                };
            }
            default -> "";
        };
    }

    private WhatsappConversation createConversation(Customer customer) {
        WhatsappConversation c = new WhatsappConversation();
        c.setCustomer(customer);
        c.setState("IDLE");
        return conversationRepository.save(c);
    }
}
