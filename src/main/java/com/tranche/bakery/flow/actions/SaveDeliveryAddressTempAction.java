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
public class SaveDeliveryAddressTempAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "SAVE_DELIVERY_ADDRESS_TEMP"; }

    @Override
    public void execute(ActionContext ctx) {
        String address = ctx.getInput().trim();
        if (address.isEmpty()) return;

        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr != null) {
            orderRepository.findById(Long.parseLong(orderIdStr)).ifPresent(order -> {
                order.setDeliveryAddress(address);
                orderRepository.save(order);
            });
        }
        log.info("Saved temporary delivery address for customer {}", ctx.getCustomer().getPhone());
    }
}
