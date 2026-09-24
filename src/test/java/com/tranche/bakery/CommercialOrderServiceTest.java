package com.tranche.bakery;

import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.CommercialOrderService;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.order.SellerProfileType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommercialOrderServiceTest extends FlowScenarioBase {

    @Autowired
    CommercialOrderService commercialOrderService;

    @Test
    void createInvoice_computesTotals_generatesInvoiceNumber_thenMarkPaidConfirms() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Ravi", "9876543210", "Cafe Aroma", LocalDate.now().plusDays(2),
                "12 MG Road", "morning drop",
                new BigDecimal("50"), SellerProfileType.COMPANY,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("350"), 6)));

        // 6 x 350 = 2100, + 50 delivery = 2150
        assertThat(order.getTotalAmount()).isEqualByComparingTo("2150");
        assertThat(order.getInvoiceNumber()).startsWith("TRB-INV-");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVOICED);
        assertThat(order.getBusinessName()).isEqualTo("Cafe Aroma");

        assertThat(commercialOrderService.invoicePdf(order.getId())).isNotEmpty();

        commercialOrderService.markPaid(order.getId());
        Order paid = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.getOrderNumber()).isNotNull();

        assertThat(commercialOrderService.receiptPdf(order.getId())).isNotEmpty();
    }

    @Test
    void createInvoice_skipsZeroQtyLines_andListsCommercialOrders() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();
        MenuItem coffee = itemRepository.findFirstByNameAndActiveTrue("Coffee & Walnut Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Bulk Buyer", "9811111111", null, LocalDate.now().plusDays(3), null, null,
                BigDecimal.ZERO, SellerProfileType.COMPANY,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("400"), 3),
                        new CommercialOrderService.Line(coffee.getId(), new BigDecimal("490"), 0)));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("1200"); // only the 3 x 400 line
        assertThat(commercialOrderService.listCommercial()).hasSize(1);
    }

    @Test
    void createInvoice_individualProfile_isStored_andInvoiceBuilds() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Partner Co", "9822222222", "Partner Co", LocalDate.now().plusDays(2), null, null,
                BigDecimal.ZERO, SellerProfileType.INDIVIDUAL,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("380"), 4)));

        assertThat(order.getSellerProfile()).isEqualTo(SellerProfileType.INDIVIDUAL);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1520");
        assertThat(commercialOrderService.invoicePdf(order.getId())).isNotEmpty();
        commercialOrderService.markPaid(order.getId());
        assertThat(commercialOrderService.receiptPdf(order.getId())).isNotEmpty();
    }
}
