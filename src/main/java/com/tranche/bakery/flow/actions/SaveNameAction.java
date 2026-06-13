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
public class SaveNameAction implements FlowAction {

    private final CustomerRepository customerRepository;

    @Override
    public String getName() { return "SAVE_NAME"; }

    @Override
    public void execute(ActionContext ctx) {
        String name = ctx.getInput().trim();
        if (name.isEmpty()) return;

        ctx.getCustomer().setName(name);
        customerRepository.save(ctx.getCustomer());
        log.info("Saved name '{}' for customer {}", name, ctx.getCustomer().getPhone());
    }
}
