package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveDeliveryAddressAction implements FlowAction {

    private final CustomerRepository customerRepository;

    @Override
    public String getName() { return "SAVE_DELIVERY_ADDRESS"; }

    @Override
    public void execute(ActionContext ctx) {
        String address = ctx.getInput().trim();
        if (address.isEmpty()) return;

        ctx.getCustomer().setDeliveryAddress(address);
        customerRepository.save(ctx.getCustomer());
        log.info("Saved delivery address for customer {}", ctx.getCustomer().getPhone());
    }
}
