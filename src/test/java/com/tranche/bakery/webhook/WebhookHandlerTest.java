package com.tranche.bakery.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.alert.AlertService;
import com.tranche.bakery.conversation.ConversationService;
import com.tranche.bakery.customer.CustomerService;

class WebhookHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String failedStatus(int code) {
        return "{\"entry\":[{\"changes\":[{\"field\":\"messages\",\"value\":{\"statuses\":[{"
                + "\"id\":\"wamid.X\",\"status\":\"failed\",\"recipient_id\":\"919811843373\","
                + "\"errors\":[{\"code\":" + code + ",\"title\":\"test\"}]}]}}]}]}";
    }

    @Test
    void marketingThrottle131049_doesNotRaiseAlert() throws Exception {
        AlertService alert = mock(AlertService.class);
        WebhookHandler h = new WebhookHandler(mock(CustomerService.class), mock(ConversationService.class), alert);
        h.handle(mapper.readTree(failedStatus(131049)));
        verify(alert, never()).raise(eq("DELIVERY_FAILURE"), anyString(), any(), anyString());
    }

    @Test
    void otherDeliveryFailure_raisesAlert() throws Exception {
        AlertService alert = mock(AlertService.class);
        WebhookHandler h = new WebhookHandler(mock(CustomerService.class), mock(ConversationService.class), alert);
        h.handle(mapper.readTree(failedStatus(131026)));
        verify(alert).raise(eq("DELIVERY_FAILURE"), anyString(), isNull(), eq("919811843373"));
    }
}
