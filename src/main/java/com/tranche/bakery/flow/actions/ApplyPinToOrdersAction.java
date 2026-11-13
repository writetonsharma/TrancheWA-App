package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApplyPinToOrdersAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "APPLY_PIN_TO_ORDERS"; }

    @Override
    public void execute(ActionContext ctx) {
        String ids = ctx.contextValue(OfferPinForUpcomingOrderAction.PIN_ORDER_IDS);
        ctx.context().remove(OfferPinForUpcomingOrderAction.PIN_ORDER_IDS);
        if (ids == null || ids.isBlank()) return;

        Customer customer = ctx.getCustomer();
        if (customer.getLocationLat() == null || customer.getLocationLng() == null) return;

        List<Order> updated = new ArrayList<>();
        for (String id : ids.split(",")) {
            orderRepository.findById(Long.parseLong(id.trim()))
                    // The id came from our own query, but re-check ownership so a stale or tampered
                    // context value can never stamp a pin onto someone else's order.
                    .filter(o -> o.getCustomer() != null && o.getCustomer().getId().equals(customer.getId()))
                    .ifPresent(order -> {
                        order.setLocationLat(customer.getLocationLat());
                        order.setLocationLng(customer.getLocationLng());
                        updated.add(order);
                    });
        }
        if (updated.isEmpty()) return;

        orderRepository.saveAll(updated);
        log.info("Applied delivery pin to {} upcoming order(s) for customer {}",
                updated.size(), customer.getPhone());
        whatsAppClient.sendText(customer.getPhone(),
                "📌 Done — the pin is now on your upcoming "
                        + (updated.size() == 1 ? "order" : "orders") + ".");
    }
}
