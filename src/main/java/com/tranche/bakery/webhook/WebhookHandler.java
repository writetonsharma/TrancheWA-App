package com.tranche.bakery.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.tranche.bakery.alert.AlertService;
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
    private final AlertService alertService;

    // Deduplication: track last 500 processed message IDs in memory
    private final Set<String> processedMessageIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 500;
                }
            })
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
                        // recipient_id + error code/title tell us WHO the failed message was for
                        // and WHY, so the dashboard alert is attributable to a real number/scenario.
                        String recipient = status.path("recipient_id").asText("unknown");
                        JsonNode firstError = errors.get(0);
                        String code  = firstError.path("code").asText("");
                        String title = firstError.path("title").asText("");
                        log.error("Message {} to {} status={} errors={}", statusId, recipient, statusVal, errors);
                        alertService.raise("DELIVERY_FAILURE",
                                "WhatsApp message to " + recipient + " failed" +
                                (code.isBlank() ? "" : " [" + code + "]") +
                                (title.isBlank() ? "" : " " + title),
                                null, recipient);
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
