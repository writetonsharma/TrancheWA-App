package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveLocationTempAction implements FlowAction {

    private final OrderRepository orderRepository;

    @Override
    public String getName() { return "SAVE_LOCATION_TEMP"; }

    @Override
    public void execute(ActionContext ctx) {
        if (ctx.getRawMessage() == null) return;

        var location = ctx.getRawMessage().path("location");
        if (location.isMissingNode()) return;

        double lat = location.path("latitude").asDouble(0);
        double lng = location.path("longitude").asDouble(0);

        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr != null) {
            orderRepository.findById(Long.parseLong(orderIdStr)).ifPresent(order -> {
                order.setLocationLat(BigDecimal.valueOf(lat));
                order.setLocationLng(BigDecimal.valueOf(lng));
                orderRepository.save(order);
            });
        }
        log.info("Saved temporary location ({}, {}) for customer {}", lat, lng, ctx.getCustomer().getPhone());
    }
}
