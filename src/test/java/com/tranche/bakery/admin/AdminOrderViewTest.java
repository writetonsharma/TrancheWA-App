package com.tranche.bakery.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderChannel;

class AdminOrderViewTest {

    @Test
    void webAddressWithoutPinDoesNotUseWhatsappCustomerCoordinates() {
        Order web = order(OrderChannel.WEB);
        web.setDeliveryAddress("Independent website delivery address");
        assertThat(view(web).mapsUrl()).isNull();
    }

    @Test
    void partialWebPinDoesNotCombineWithCustomerCoordinates() {
        Order web = order(OrderChannel.WEB);
        web.setLocationLat(new BigDecimal("28.500000"));
        assertThat(view(web).mapsUrl()).isNull();
        web.setLocationLat(null);
        web.setLocationLng(new BigDecimal("77.200000"));
        assertThat(view(web).mapsUrl()).isNull();
    }

    @Test
    void webOrderWithItsOwnCompletePinUsesThatPin() {
        Order web = order(OrderChannel.WEB);
        web.setLocationLat(new BigDecimal("28.500000"));
        web.setLocationLng(new BigDecimal("77.200000"));
        assertThat(view(web).mapsUrl()).isEqualTo("https://maps.google.com/?q=28.500000,77.200000");
    }

    @Test
    void whatsappFallbackAndOrderPinPrecedenceAreUnchanged() {
        Order legacy = order(OrderChannel.WHATSAPP);
        assertThat(view(legacy).mapsUrl()).isEqualTo("https://maps.google.com/?q=28.450000,77.100000");
        legacy.setLocationLat(new BigDecimal("28.500000"));
        legacy.setLocationLng(new BigDecimal("77.200000"));
        assertThat(view(legacy).mapsUrl()).isEqualTo("https://maps.google.com/?q=28.500000,77.200000");
    }

    private static AdminOrderView view(Order order) {
        return AdminOrderView.of(order, List.of(), null);
    }

    private static Order order(OrderChannel channel) {
        Customer customer = new Customer();
        customer.setLocationLat(new BigDecimal("28.450000"));
        customer.setLocationLng(new BigDecimal("77.100000"));
        Order order = new Order();
        order.setChannel(channel);
        order.setCustomer(customer);
        return order;
    }
}