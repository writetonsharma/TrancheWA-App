package com.tranche.bakery;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standalone "update my delivery pin" journey, reached from the wa.me deep link on the
 * website or from the Info menu. Unlike the in-order pin prompt, it must update the customer
 * default without touching any order the customer did not explicitly agree to.
 */
class DeliveryPinUpdateFlowTest extends FlowScenarioBase {

    private static final String DEEP_LINK = "Update my delivery pin";

    private Order upcomingOrderWithoutPin(LocalDate deliveryDate) {
        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setDeliveryDate(deliveryDate);
        order.setTotalAmount(new BigDecimal("500.00"));
        return orderRepository.save(order);
    }

    private Customer reloadCustomer() {
        return customerRepository.findById(customer.getId()).orElseThrow();
    }

    @Test
    @DisplayName("deep-link phrase at IDLE opens the pin update instead of resetting to the greeting")
    void deepLinkFromIdle() {
        send(DEEP_LINK);
        assertState("PIN_UPDATE");
        assertThat(sentButtonBodies).anyMatch(b -> b.contains("delivery pin"));
    }

    @Test
    @DisplayName("deep-link phrase at MAIN_MENU is matched before the catch-all transition")
    void deepLinkFromMainMenu() {
        send("hi");
        assertState("MAIN_MENU");
        send(DEEP_LINK);
        assertState("PIN_UPDATE");
    }

    @Test
    @DisplayName("Info menu offers a pin update button")
    void infoMenuButton() {
        send("hi");
        send("info");
        assertThat(sentButtonTitles).contains("Update my pin");
        send("update_pin");
        assertState("PIN_UPDATE");
    }

    @Test
    @DisplayName("sharing a pin with nothing upcoming saves the customer default and returns to the menu")
    void savesCustomerDefault() {
        send(DEEP_LINK);
        sendLocation("28.500000", "77.200000");

        Customer saved = reloadCustomer();
        assertThat(saved.getLocationLat()).isEqualByComparingTo("28.500000");
        assertThat(saved.getLocationLng()).isEqualByComparingTo("77.200000");
        assertThat(sentTexts).anyMatch(t -> t.contains("delivery pin is saved"));
        assertState("MAIN_MENU");
    }

    @Test
    @DisplayName("an upcoming unpinned order is offered the new pin and accepts it")
    void appliesPinToUpcomingOrderOnYes() {
        Order order = upcomingOrderWithoutPin(LocalDate.now().plusDays(2));

        send(DEEP_LINK);
        sendLocation("28.500000", "77.200000");

        assertState("PIN_UPDATE_SAVED");
        assertThat(sentButtonTitles).contains("Yes, apply it");

        send("pin_apply_yes");
        assertState("MAIN_MENU");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getLocationLat()).isEqualByComparingTo("28.500000");
        assertThat(reloaded.getLocationLng()).isEqualByComparingTo("77.200000");
    }

    @Test
    @DisplayName("declining leaves the upcoming order unpinned and clears the pending ids")
    void leavesOrderUnpinnedOnNo() {
        Order order = upcomingOrderWithoutPin(LocalDate.now().plusDays(2));

        send(DEEP_LINK);
        sendLocation("28.500000", "77.200000");
        send("pin_apply_no");

        assertState("MAIN_MENU");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getLocationLat()).isNull();
        assertThat(reloadCustomer().getLocationLat()).isEqualByComparingTo("28.500000");
    }

    @Test
    @DisplayName("a past order and an already-pinned order are never offered")
    void ignoresPastAndPinnedOrders() {
        Order past = upcomingOrderWithoutPin(LocalDate.now().minusDays(3));
        Order pinned = upcomingOrderWithoutPin(LocalDate.now().plusDays(2));
        pinned.setLocationLat(new BigDecimal("28.111111"));
        pinned.setLocationLng(new BigDecimal("77.111111"));
        orderRepository.save(pinned);

        send(DEEP_LINK);
        sendLocation("28.500000", "77.200000");

        assertState("MAIN_MENU");
        assertThat(orderRepository.findById(past.getId()).orElseThrow().getLocationLat()).isNull();
        assertThat(orderRepository.findById(pinned.getId()).orElseThrow().getLocationLat())
                .isEqualByComparingTo("28.111111");
    }

    @Test
    @DisplayName("a stale orderId in conversation context is never re-pinned")
    void ignoresStaleOrderIdInContext() {
        Order completed = new Order();
        completed.setCustomer(customer);
        completed.setStatus(OrderStatus.COMPLETED);
        completed.setDeliveryDate(LocalDate.now().minusDays(1));
        completed.setTotalAmount(new BigDecimal("500.00"));
        completed = orderRepository.save(completed);

        conversation.getContext().put("orderId", completed.getId().toString());
        conversationRepository.save(conversation);

        send(DEEP_LINK);
        sendLocation("28.500000", "77.200000");

        assertThat(orderRepository.findById(completed.getId()).orElseThrow().getLocationLat()).isNull();
        assertThat(reloadCustomer().getLocationLat()).isEqualByComparingTo("28.500000");
    }

    @Test
    @DisplayName("Not now leaves the stored pin untouched")
    void skipKeepsExistingPin() {
        send(DEEP_LINK);
        send("pin_skip");

        assertState("MAIN_MENU");
        assertThat(reloadCustomer().getLocationLat()).isEqualByComparingTo("28.456789");
    }
}
