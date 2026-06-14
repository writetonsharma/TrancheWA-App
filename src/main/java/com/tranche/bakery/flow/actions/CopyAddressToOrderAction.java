package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CopyAddressToOrderAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "COPY_ADDRESS_TO_ORDER"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        orderRepository.findById(Long.parseLong(orderIdStr)).ifPresent(order -> {
            order.setDeliveryAddress(ctx.getCustomer().getDeliveryAddress());
            order.setLocationLat(ctx.getCustomer().getLocationLat());
            order.setLocationLng(ctx.getCustomer().getLocationLng());
            orderRepository.save(order);
            log.info("Copied default address to order {}", order.getId());
        });
    }
}
