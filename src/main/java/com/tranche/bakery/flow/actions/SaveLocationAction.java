package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveLocationAction implements FlowAction {

    private final CustomerRepository customerRepository;

    @Override
    public String getName() { return "SAVE_LOCATION"; }

    @Override
    public void execute(ActionContext ctx) {
        if (ctx.getRawMessage() == null) return;

        var location = ctx.getRawMessage().path("location");
        if (location.isMissingNode()) return;

        double lat = location.path("latitude").asDouble(0);
        double lng = location.path("longitude").asDouble(0);

        ctx.getCustomer().setLocationLat(BigDecimal.valueOf(lat));
        ctx.getCustomer().setLocationLng(BigDecimal.valueOf(lng));
        customerRepository.save(ctx.getCustomer());
        log.info("Saved location ({}, {}) for customer {}", lat, lng, ctx.getCustomer().getPhone());
    }
}
