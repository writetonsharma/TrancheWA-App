package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.Customer;
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
public class SavePinOnlyAction implements FlowAction {

    private final CustomerRepository customerRepository;

    @Override
    public String getName() { return "SAVE_PIN_ONLY"; }

    // Standalone re-pin. Deliberately never reads orderId from conversation context: a stale id
    // left by a finished order would silently re-pin that order without the customer asking.
    @Override
    public void execute(ActionContext ctx) {
        var location = ctx.getRawMessage() == null ? null : ctx.getRawMessage().path("location");
        if (location == null || location.isMissingNode()) {
            ctx.setRedirectState("PIN_UPDATE");
            return;
        }

        double lat = location.path("latitude").asDouble(0);
        double lng = location.path("longitude").asDouble(0);

        Customer customer = ctx.getCustomer();
        customer.setLocationLat(BigDecimal.valueOf(lat));
        customer.setLocationLng(BigDecimal.valueOf(lng));
        customerRepository.save(customer);

        log.info("Updated delivery pin ({}, {}) for customer {}", lat, lng, customer.getPhone());
    }
}
