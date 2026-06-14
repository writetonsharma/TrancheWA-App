package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.delivery.DeliveryAreaLoader;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveAreaAction implements FlowAction {

    private final CustomerRepository customerRepository;
    private final DeliveryAreaLoader deliveryAreaLoader;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SAVE_AREA"; }

    @Override
    public void execute(ActionContext ctx) {
        String pincode = ctx.getInput().trim();
        DeliveryAreaLoader.DeliveryArea area = deliveryAreaLoader.findByPincode(pincode);

        if (area == null) {
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "Thank you for checking. We do not currently deliver to pincode *" + pincode + "*.\n\n" +
                    "At the moment, we serve: " + deliveryAreaLoader.areaNamesSummary() + ".\n\n" +
                    "You may try another pincode, or send *hi* to return to the main menu.");
            ctx.setRedirectState("AREA_SELECT");
            return;
        }

        ctx.getCustomer().setDeliveryArea(area.name());
        customerRepository.save(ctx.getCustomer());
        log.info("Saved delivery area '{}' (pincode {}) for customer {}",
                area.name(), pincode, ctx.getCustomer().getPhone());
    }
}
