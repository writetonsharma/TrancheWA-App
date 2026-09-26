package com.tranche.bakery;

import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.CommercialOrderService;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.order.SellerProfileType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommercialOrderServiceTest extends FlowScenarioBase {

    @Autowired
    CommercialOrderService commercialOrderService;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    AdminService adminService;

    @Test
    void createInvoice_computesTotals_generatesInvoiceNumber_thenMarkPaidConfirms() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Ravi", "9876543210", "Cafe Aroma", LocalDate.now().plusDays(2),
                "12 MG Road", "morning drop",
                new BigDecimal("50"), SellerProfileType.COMPANY, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("350"), 6, false)));

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
                BigDecimal.ZERO, SellerProfileType.COMPANY, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("400"), 3, false),
                        new CommercialOrderService.Line(coffee.getId(), new BigDecimal("490"), 0, false)));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("1200"); // only the 3 x 400 line
        assertThat(commercialOrderService.listCommercial()).hasSize(1);
    }

    @Test
    void createInvoice_individualProfile_isStored_andInvoiceBuilds() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Partner Co", "9822222222", "Partner Co", LocalDate.now().plusDays(2), null, null,
                BigDecimal.ZERO, SellerProfileType.INDIVIDUAL, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("380"), 4, false)));

        assertThat(order.getSellerProfile()).isEqualTo(SellerProfileType.INDIVIDUAL);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1520");
        assertThat(commercialOrderService.invoicePdf(order.getId())).isNotEmpty();
        commercialOrderService.markPaid(order.getId());
        assertThat(commercialOrderService.receiptPdf(order.getId())).isNotEmpty();
    }

    @Test
    void createInvoice_discountedLine_snapshotsListPrice_andInvoiceBuilds() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        // list price 400; charge 350 (₹50 off each) x 6 units
        Order order = commercialOrderService.createInvoice(
                "Disc Buyer", "9833333333", null, LocalDate.now().plusDays(2), null, null,
                BigDecimal.ZERO, SellerProfileType.COMPANY, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("350"), 6, false)));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("2100"); // 6 x 350 charged
        var items = orderItemRepository.findAllByOrderId(order.getId());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getListUnitPrice()).isEqualByComparingTo(lemon.getPrice()); // 400 snapshot
        assertThat(items.get(0).getUnitPrice()).isEqualByComparingTo("350");
        assertThat(commercialOrderService.invoicePdf(order.getId())).isNotEmpty();
    }

    @Test
    void createInvoice_complimentaryLine_isFree_andExcludedFromTotal() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();
        MenuItem knots = itemRepository.findFirstByNameAndActiveTrue("Garlic & Herb Knots").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Gift Buyer", "9844444444", null, LocalDate.now().plusDays(2), null, null,
                BigDecimal.ZERO, SellerProfileType.COMPANY, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("400"), 2, false),
                        new CommercialOrderService.Line(knots.getId(), null, 1, true)));

        assertThat(order.getTotalAmount()).isEqualByComparingTo("800"); // 2 x 400; complimentary knots add 0
        var comp = orderItemRepository.findAllByOrderId(order.getId()).stream()
                .filter(i -> "Complimentary".equalsIgnoreCase(i.getNote())).findFirst().orElseThrow();
        assertThat(comp.getUnitPrice()).isEqualByComparingTo("0");
        assertThat(comp.getSubtotal()).isEqualByComparingTo("0");
        assertThat(commercialOrderService.invoicePdf(order.getId())).isNotEmpty();
    }

    @Test
    void silentCommercialOrder_statusChange_doesNotMessageCustomer_untilToggledOn() {
        MenuItem lemon = itemRepository.findFirstByNameAndActiveTrue("Lemon Tea Cake").orElseThrow();

        Order order = commercialOrderService.createInvoice(
                "Silent Co", "9855555555", null, LocalDate.now().plusDays(1), null, null,
                BigDecimal.ZERO, SellerProfileType.COMPANY, false,
                List.of(new CommercialOrderService.Line(lemon.getId(), new BigDecimal("400"), 1, false)));
        assertThat(order.isNotifyCustomer()).isFalse();
        commercialOrderService.markPaid(order.getId());

        Mockito.reset(whatsAppClient);
        adminService.markInBaking(order.getId());
        Mockito.verifyNoInteractions(whatsAppClient); // silent order: no WhatsApp update

        assertThat(adminService.toggleNotify(order.getId())).isTrue(); // flip on for future updates
    }
}
