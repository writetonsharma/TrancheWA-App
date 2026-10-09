package com.tranche.bakery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.actions.RouteToDeliveryPreferenceOrSkipAction;
import com.tranche.bakery.flow.actions.RouteToLoafPreferenceOrConfirmAction;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderItem;
import com.tranche.bakery.order.OrderItemRepository;
import com.tranche.bakery.order.OrderStatus;

/** Returning-customer fast path: reuse delivery + slice preference from the last order. */
class OrderPrefReuseTest extends FlowScenarioBase {

    @Autowired RouteToDeliveryPreferenceOrSkipAction deliveryRouter;
    @Autowired RouteToLoafPreferenceOrConfirmAction loafRouter;
    @Autowired OrderItemRepository orderItemRepository;

    private void priorOrderWith(String deliveryPref, String loafPref) {
        Order o = new Order();
        o.setCustomer(customer);
        o.setStatus(OrderStatus.CONFIRMED);
        o.setDeliveryPreference(deliveryPref);
        o.setLoafPreference(loafPref);
        orderRepository.save(o);
    }

    private Order newDraft(boolean withLoaf) {
        Order o = new Order();
        o.setCustomer(customer);
        o.setStatus(OrderStatus.DRAFT);
        o = orderRepository.save(o);
        if (withLoaf) {
            MenuItem loaf = itemRepository.findAllByCategoryAndActiveTrueOrderByDisplayOrderAsc(
                    categoryRepository.findAllByActiveTrueOrderByDisplayOrderAsc().get(0)).get(0);
            OrderItem it = new OrderItem();
            it.setOrder(o);
            it.setMenuItem(loaf);
            it.setQuantity(1);
            it.setUnitPrice(loaf.getPrice() == null ? BigDecimal.ZERO : loaf.getPrice());
            it.setSubtotal(loaf.getPrice() == null ? BigDecimal.ZERO : loaf.getPrice());
            orderItemRepository.save(it);
        }
        return o;
    }

    private ActionContext ctxFor(Order order) {
        conversation.getContext().put("orderId", order.getId().toString());
        conversationRepository.save(conversation);
        return ActionContext.builder().customer(customer).conversation(conversation).build();
    }

    @Test
    void deliveryPref_reusedFromLastOrder_skipsStep() {
        priorOrderWith("GATE", "SLICED");
        Order current = newDraft(false);
        ActionContext ctx = ctxFor(current);
        deliveryRouter.execute(ctx);
        assertThat(ctx.getRedirectState()).isEqualTo("LOAF_PREFERENCE_GATE");
        assertThat(orderRepository.findById(current.getId()).orElseThrow().getDeliveryPreference()).isEqualTo("GATE");
    }

    @Test
    void deliveryPref_firstTime_asksTheQuestion() {
        Order current = newDraft(false);
        ActionContext ctx = ctxFor(current);
        deliveryRouter.execute(ctx);
        assertThat(ctx.getRedirectState()).isNull(); // no redirect → DELIVERY_PREFERENCE question is shown
    }

    @Test
    void slicePref_reusedFromLastOrder_skipsStep() {
        priorOrderWith("GATE", "SLICED");
        Order current = newDraft(true);
        ActionContext ctx = ctxFor(current);
        loafRouter.execute(ctx);
        assertThat(ctx.getRedirectState()).isEqualTo("ORDER_CONFIRM");
        assertThat(orderRepository.findById(current.getId()).orElseThrow().getLoafPreference()).isEqualTo("SLICED");
    }

    @Test
    void slicePref_firstTimeWithLoaves_asksTheQuestion() {
        Order current = newDraft(true);
        ActionContext ctx = ctxFor(current);
        loafRouter.execute(ctx);
        assertThat(ctx.getRedirectState()).isEqualTo("LOAF_PREFERENCE");
    }
}
