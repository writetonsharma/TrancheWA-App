package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveDeliveryDateAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "SAVE_DELIVERY_DATE"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        String dateStr = ctx.contextValue("deliveryDate");
        if (orderIdStr == null || dateStr == null) return;

        Order order = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (order == null) return;

        order.setDeliveryDate(LocalDate.parse(dateStr));
        orderRepository.save(order);
        log.info("Delivery date {} saved for order {}", dateStr, order.getId());
    }
}
