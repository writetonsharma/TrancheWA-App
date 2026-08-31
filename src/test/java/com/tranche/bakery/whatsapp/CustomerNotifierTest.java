package com.tranche.bakery.whatsapp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.receipt.ReceiptService;

/** Unit tests for the window-based free-form vs Utility-template routing, with a mocked client. */
class CustomerNotifierTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM");
    private static final LocalDate DELIVERY = LocalDate.of(2026, 8, 30);
    private final String expectedDate = DELIVERY.format(DATE_FMT);

    private static final LocalDateTime IN_WINDOW = LocalDateTime.now();
    private static final LocalDateTime OUT_OF_WINDOW = LocalDateTime.now().minusHours(30);

    private Order order(String name, String phone, String ref, LocalDateTime lastInbound) {
        Customer c = new Customer();
        c.setName(name);
        c.setPhone(phone);
        c.setLastInboundAt(lastInbound);
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

        new CustomerNotifier(client, receipt).orderInBaking(order("Naveen Sharma", "9199", "TRB-1", IN_WINDOW));

        verify(client).sendText(eq("9199"), contains("in the oven"));
        verify(client, never()).sendTemplate(any(), any(), any());
    }

    @Test
    void inBaking_outOfWindow_sendsTemplate() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);

        new CustomerNotifier(client, receipt).orderInBaking(order("Naveen Sharma", "9199", "TRB-1", OUT_OF_WINDOW));

        verify(client).sendTemplate("9199", "order_in_baking", List.of("Naveen", "TRB-1", expectedDate));
        verify(client, never()).sendText(any(), any());
    }

    @Test
    void outForDelivery_outOfWindow_sendsTemplate() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);

        new CustomerNotifier(client, receipt).orderOutForDelivery(order("Naveen", "9199", "TRB-1", OUT_OF_WINDOW));

        verify(client).sendTemplate("9199", "order_out_for_delivery", List.of("Naveen", "TRB-1"));
        verify(client, never()).sendText(any(), any());
    }

    @Test
    void confirmed_outOfWindow_sendsTemplateCarryingTheReceipt() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(receipt.prepare(any())).thenReturn(new ReceiptService.ReceiptMedia("MID", "r.pdf"));

        new CustomerNotifier(client, receipt).orderConfirmed(order("Naveen", "9199", "TRB-1", OUT_OF_WINDOW));

        verify(client).sendTemplateWithDocument("9199", "order_confirmation", "MID", "r.pdf",
                List.of("Naveen", "TRB-1", expectedDate));
        verify(client, never()).sendText(any(), any());
        verify(client, never()).sendDocument(any(), any(), any(), any());
    }

    @Test
    void confirmed_inWindow_sendsReceiptDocThenFreeFormText() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);
        when(receipt.prepare(any())).thenReturn(new ReceiptService.ReceiptMedia("MID", "r.pdf"));

        new CustomerNotifier(client, receipt).orderConfirmed(order("Naveen", "9199", "TRB-1", IN_WINDOW));

        verify(client).sendDocument(eq("9199"), eq("MID"), eq("r.pdf"), any());
        verify(client).sendText(eq("9199"), contains("order confirmed"));
        verify(client, never()).sendTemplateWithDocument(any(), any(), any(), any(), any());
    }

    @Test
    void cancelled_outOfWindow_usesDefaultReasonWhenNoneGiven() {
        WhatsAppClient client = mock(WhatsAppClient.class);
        ReceiptService receipt = mock(ReceiptService.class);

        new CustomerNotifier(client, receipt).orderCancelled(order("Naveen", "9199", "TRB-1", OUT_OF_WINDOW), null);

        verify(client).sendTemplate("9199", "order_cancelled",
                List.of("Naveen", "TRB-1", "Cancelled by the bakery."));
    }
}
