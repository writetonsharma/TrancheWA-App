package com.tranche.bakery.receipt;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.FulfillmentType;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring context): verifies the receipt PDF renders to valid,
 * non-empty PDF bytes for a fully-featured paid order and for the minimal case.
 */
class ReceiptPdfServiceTest {

    private final OrderItemRepository itemRepo = mock(OrderItemRepository.class);

    private ReceiptProperties props() {
        ReceiptProperties p = new ReceiptProperties();
        p.setEnabled(true);
        p.setBusinessName("Tranche");
        p.setTagline("Artisan Bakery");
        p.setContactPhone("+91 87967 70308");
        p.setLocation("Gurugram, Sector-15 \u00b7 Serving DLF Phase 5");
        p.setFssai("20826005005996");
        return p;
    }

    private MenuItem item(String name, String price) {
        MenuItem m = new MenuItem();
        m.setName(name);
        m.setPrice(new BigDecimal(price));
        return m;
    }

    private OrderItem line(Order o, MenuItem m, int qty, String subtotal) {
        OrderItem oi = new OrderItem();
        oi.setOrder(o);
        oi.setMenuItem(m);
        oi.setQuantity(qty);
        oi.setUnitPrice(m.getPrice());
        oi.setSubtotal(new BigDecimal(subtotal));
        return oi;
    }

    @Test
    void buildsValidPdfForFullyFeaturedOrder() {
        Customer c = new Customer();
        c.setName("Aarav Sharma");
        c.setPhone("919999999999");

        Order o = new Order();
        o.setId(101L);
        o.setCustomer(c);
        o.setOrderNumber("TB-1042");
        o.setFulfillmentType(FulfillmentType.DELIVERY);
        o.setDeliveryDate(LocalDate.of(2026, 7, 20));
        o.setDeliveryAddress("DLF Phase 5, Gurgaon");
        o.setDiscountAmount(new BigDecimal("120"));
        o.setDiscountLabel("Launch 20% off");
        o.setBatchDiscountAmount(new BigDecimal("24"));
        o.setBatchDiscountLabel("Batch discount +4%");
        o.setDeliveryCharge(BigDecimal.ZERO);
        o.setGiftLabel("Free sweet roll");
        o.setTotalAmount(new BigDecimal("456"));

        MenuItem loaf = item("Multi-Seed Loaf", "300");
        MenuItem foc = item("Rosemary Focaccia", "600");
        when(itemRepo.findAllByOrderId(101L)).thenReturn(List.of(
                line(o, loaf, 1, "300"),
                line(o, foc, 1, "600")));

        byte[] pdf = new ReceiptPdfService(props(), itemRepo).build(o);

        assertThat(pdf).isNotEmpty();
        String head = new String(pdf, 0, 5, StandardCharsets.ISO_8859_1);
        assertThat(head).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(800);
    }

    @Test
    void buildsValidPdfForMinimalOrder() {
        Customer c = new Customer();
        c.setPhone("919999999999");

        Order o = new Order();
        o.setId(7L);
        o.setCustomer(c);
        o.setFulfillmentType(FulfillmentType.DELIVERY);
        o.setDeliveryCharge(new BigDecimal("50"));
        o.setTotalAmount(new BigDecimal("290"));

        MenuItem bagel = item("Sesame Bagel", "120");
        when(itemRepo.findAllByOrderId(7L)).thenReturn(List.of(line(o, bagel, 2, "240")));

        byte[] pdf = new ReceiptPdfService(props(), itemRepo).build(o);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }
}
