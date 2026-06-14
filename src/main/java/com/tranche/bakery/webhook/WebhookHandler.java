package com.tranche.bakery.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.conversation.ConversationService;
import com.tranche.bakery.customer.CustomerService;
import com.tranche.bakery.customer.Customer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookHandler {

    private final CustomerService customerService;
    private final ConversationService conversationService;

    // Deduplication: track last 500 processed message IDs in memory
    private final Set<String> processedMessageIds = Collections.newSetFromMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            }
    );

    public void handle(JsonNode payload) {
        JsonNode entries = payload.path("entry");
        if (entries.isMissingNode()) return;

        for (JsonNode entry : entries) {
            for (JsonNode change : entry.path("changes")) {
                if (!"messages".equals(change.path("field").asText())) continue;

                JsonNode value = change.path("value");

                // Log delivery status updates (sent/delivered/read/failed)
                for (JsonNode status : value.path("statuses")) {
                    String statusId  = status.path("id").asText();
                    String statusVal = status.path("status").asText();
                    JsonNode errors  = status.path("errors");
                    if (!errors.isMissingNode() && errors.isArray() && !errors.isEmpty()) {
                        log.error("Message {} status={} errors={}", statusId, statusVal, errors);
                    } else {
                        log.info("Message {} status={}", statusId, statusVal);
                    }
                }
                

                for (JsonNode message : value.path("messages")) {
                    String messageId = message.path("id").asText("");
                    if (!messageId.isEmpty() && !processedMessageIds.add(messageId)) {
                        log.debug("Skipping duplicate message id={}", messageId);
                        continue;
                    }

                    String from = message.path("from").asText();
                    String type = message.path("type").asText();
                    log.info("Incoming message id={} from={} type={}", messageId, from, type);

                    Customer customer = customerService.findOrCreate(from);
                    conversationService.handle(customer, type, message);
                }
            }
        }
    }
}
