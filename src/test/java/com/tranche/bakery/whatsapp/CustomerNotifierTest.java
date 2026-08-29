package com.tranche.bakery.whatsapp;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.receipt.ReceiptService;

/** Unit tests for the free-form-primary / template-fallback behaviour, with a mocked client. */
class CustomerNotifierTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");
    private static final LocalDate DELIVERY = LocalDate.of(2026, 8, 30);
    private final String expectedDate = DELIVERY.format(DATE_FMT);

    private Order order(String name, String phone, String ref) {
        Customer c = new Customer();
        c.setName(name);
        c.setPhone(phone);
        Order o = new Order();
        o.setId(1L);
        o.setOrderNumber(ref);
        o.setDeliveryDate(DELIVERY);
        o.setCustomer(c);
        return o;
    }

    @Test
    void inBaking_inWindow_sendsFreeFormOnly() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(client.sendText(any(), any())).thenReturn(SendOutcome.SENT);

        new CustomerNotifier(client, receipt).orderInBaking(order("Naveen Sharma", "9199", "TRB-1"));

        verify(client).sendText(eq("9199"), contains("in the oven"));
        verify(client, never()).sendTemplate(any(), any(), any());
    }

    @Test
    void inBaking_windowClosed_fallsBackToTemplate() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(client.sendText(any(), any())).thenReturn(SendOutcome.WINDOW_CLOSED);

        new CustomerNotifier(client, receipt).orderInBaking(order("Naveen Sharma", "9199", "TRB-1"));

        verify(client).sendTemplate("9199", "order_in_baking", List.of("Naveen", "TRB-1", expectedDate));
    }

    @Test
    void outForDelivery_windowClosed_fallsBackToTemplate() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(client.sendText(any(), any())).thenReturn(SendOutcome.WINDOW_CLOSED);

        new CustomerNotifier(client, receipt).orderOutForDelivery(order("Naveen", "9199", "TRB-1"));

        verify(client).sendTemplate("9199", "order_out_for_delivery", List.of("Naveen", "TRB-1"));
    }

    @Test
    void confirmed_windowClosed_sendsTemplateCarryingTheReceipt() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(receipt.prepare(any())).thenReturn(new ReceiptService.ReceiptMedia("MID", "r.pdf"));
        when(client.sendDocument(any(), any(), any(), any())).thenReturn(SendOutcome.WINDOW_CLOSED);

        new CustomerNotifier(client, receipt).orderConfirmed(order("Naveen", "9199", "TRB-1"));

        verify(client).sendTemplateWithDocument("9199", "order_confirmation", "MID", "r.pdf",
                List.of("Naveen", "TRB-1", expectedDate));
        verify(client, never()).sendText(any(), any());
    }

    @Test
    void confirmed_inWindow_sendsReceiptDocThenFreeFormText() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(receipt.prepare(any())).thenReturn(new ReceiptService.ReceiptMedia("MID", "r.pdf"));
        when(client.sendDocument(any(), any(), any(), any())).thenReturn(SendOutcome.SENT);

        new CustomerNotifier(client, receipt).orderConfirmed(order("Naveen", "9199", "TRB-1"));

        verify(client).sendDocument(eq("9199"), eq("MID"), eq("r.pdf"), any());
        verify(client).sendText(eq("9199"), contains("order confirmed"));
        verify(client, never()).sendTemplateWithDocument(any(), any(), any(), any(), any());
    }

    @Test
    void cancelled_windowClosed_usesDefaultReasonWhenNoneGiven() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(client.sendText(any(), any())).thenReturn(SendOutcome.WINDOW_CLOSED);

        new CustomerNotifier(client, receipt).orderCancelled(order("Naveen", "9199", "TRB-1"), null);

        verify(client).sendTemplate("9199", "order_cancelled",
                List.of("Naveen", "TRB-1", "Cancelled by the bakery."));
    }
}
