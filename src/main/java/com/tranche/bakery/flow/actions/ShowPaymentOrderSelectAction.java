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
public class ShowPaymentOrderSelectAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    @Override
    public String getName() { return "SHOW_PAYMENT_ORDER_SELECT"; }

    @Override
    public void execute(ActionContext ctx) {
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(
                ctx.getCustomer().getId(), OrderStatus.PENDING_CONFIRMATION);

        if (pending.isEmpty()) return;

        List<WhatsAppMessage.Button> buttons = pending.stream()
                .map(o -> {
                    String ref   = o.getOrderNumber() != null ? o.getOrderNumber() : "#" + o.getId();
                    String date  = o.getDeliveryDate() != null ? o.getDeliveryDate().format(DATE_FMT) : "TBD";
                    String amt   = o.getTotalAmount() != null ? "₹" + o.getTotalAmount().stripTrailingZeros().toPlainString() : "";
                    String title = ref + " · " + date + (amt.isEmpty() ? "" : " · " + amt);
                    // Button title max 20 chars — fall back to short form if needed
                    if (title.length() > 20) title = ref + " · " + date;
                    if (title.length() > 20) title = ref;
                    return new WhatsAppMessage.Button("pay_" + o.getId(), title);
                })
                .toList();

        whatsAppClient.sendButtons(ctx.getCustomer().getPhone(),
                "Got your payment screenshot! 📸\n\nYou have multiple orders awaiting payment — " +
                "which order is this payment for?",
                buttons);
    }
}
