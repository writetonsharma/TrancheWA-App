package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveDeliveryPreferenceAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "SAVE_DELIVERY_PREFERENCE"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        String input = ctx.getInput().trim();
        String preference = switch (input) {
            case "pref_gate" -> "GATE";
            case "pref_door" -> "DOOR";
            case "pref_person" -> "IN_PERSON";
            default -> input;
        };

        orderRepository.findById(Long.parseLong(orderIdStr)).ifPresent(order -> {
            order.setDeliveryPreference(preference);
            orderRepository.save(order);
            log.info("Delivery preference set to {} for order {}", preference, order.getId());
        });
    }
}
