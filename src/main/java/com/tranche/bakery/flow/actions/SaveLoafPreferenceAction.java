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
public class SaveLoafPreferenceAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "SAVE_LOAF_PREFERENCE"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderId = ctx.contextValue("orderId");
        if (orderId == null) return;

        String preference = switch (ctx.getInput().trim()) {
            case "loaf_sliced" -> "SLICED";
            case "loaf_whole" -> "WHOLE";
            default -> ctx.getInput().trim();
        };

        orderRepository.findById(Long.parseLong(orderId)).ifPresent(order -> {
            order.setLoafPreference(preference);
            orderRepository.save(order);
            log.info("Loaf preference set to {} for order {}", preference, order.getId());
        });
    }
}