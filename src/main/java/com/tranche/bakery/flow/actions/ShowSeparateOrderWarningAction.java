package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class ShowSeparateOrderWarningAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH);

    @Override
    public String getName() { return "SHOW_SEPARATE_ORDER_WARNING"; }

    @Override
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        if (orderIdStr == null) return;

        Order draft = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (draft == null || draft.getDeliveryDate() == null) return;

        long existingCount = orderRepository
                .findAllByCustomerIdAndStatus(ctx.getCustomer().getId(), OrderStatus.PENDING_CONFIRMATION)
                .size();

        String dateFormatted = draft.getDeliveryDate().format(DATE_FMT);
        int newOrderNumber = (int) existingCount + 1;

        String body = "📦 *New separate order — " + dateFormatted + "*\n\n" +
                "This will be order *#" + newOrderNumber + "* for you, each with its own payment. " +
                "You currently have *" + existingCount + "* order" +
                (existingCount == 1 ? "" : "s") + " awaiting payment.\n\n" +
                "Would you like to continue?";

        whatsAppClient.sendButtons(ctx.getCustomer().getPhone(), body,
                List.of(
                        new WhatsAppMessage.Button("continue_order", "Continue"),
                        new WhatsAppMessage.Button("cancel_order",   "Cancel")
                ));
    }
}
