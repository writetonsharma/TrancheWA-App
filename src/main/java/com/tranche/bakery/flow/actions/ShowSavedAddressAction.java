package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ShowSavedAddressAction implements FlowAction {

    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "SHOW_SAVED_ADDRESS"; }

    @Override
    public void execute(ActionContext ctx) {
        String savedAddress = ctx.getCustomer().getDeliveryAddress();
        whatsAppClient.sendText(
                ctx.getCustomer().getPhone(),
                "We have your delivery address on file:\n\n_" + savedAddress + "_"
        );
        whatsAppClient.sendButtons(
                ctx.getCustomer().getPhone(),
                "Use this address for delivery?",
                List.of(
                        new WhatsAppMessage.Button("use_address",    "Use This Address"),
                        new WhatsAppMessage.Button("change_address", "Different Address")
                )
        );
    }
}
