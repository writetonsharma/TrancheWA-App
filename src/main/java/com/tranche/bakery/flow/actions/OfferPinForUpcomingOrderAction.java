package com.tranche.bakery.flow.actions;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.DeliveryRules;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import com.tranche.bakery.whatsapp.WhatsAppMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class OfferPinForUpcomingOrderAction implements FlowAction {

    static final String PIN_ORDER_IDS = "pinOrderIds";

    private final OrderRepository orderRepository;
    private final WhatsAppClient whatsAppClient;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);

    @Override
    public String getName() { return "OFFER_PIN_FOR_UPCOMING_ORDER"; }

    // A web order never inherits the customer's default pin, so ask before copying the new pin
    // onto it — the delivery address on that order may not be the one just pinned.
    @Override
    public void execute(ActionContext ctx) {
        Customer customer = ctx.getCustomer();
        List<Order> unpinned = orderRepository
                .findAllByCustomerIdAndStatusInAndDeliveryDateGreaterThanEqualAndLocationLatIsNullOrderByDeliveryDateAsc(
                        customer.getId(), DeliveryRules.capacityStatuses(), LocalDate.now());

        if (unpinned.isEmpty()) {
            ctx.context().remove(PIN_ORDER_IDS);
            whatsAppClient.sendText(customer.getPhone(),
                    "✅ Thank you — your delivery pin is saved. We'll use it for your next order.");
            ctx.setRedirectState("MAIN_MENU");
            return;
        }

        ctx.context().put(PIN_ORDER_IDS, unpinned.stream()
                .map(o -> o.getId().toString())
                .collect(Collectors.joining(",")));

        String dates = unpinned.stream()
                .map(o -> o.getDeliveryDate().format(DATE_FMT))
                .collect(Collectors.joining(", "));
        String body = "✅ Thank you — your delivery pin is saved.\n\n" +
                "You have " + (unpinned.size() == 1 ? "an upcoming order" : unpinned.size() + " upcoming orders")
                + " without a pin (*" + dates + "*).\n\n" +
                "Shall we use this pin for " + (unpinned.size() == 1 ? "it" : "them") + " too?";

        whatsAppClient.sendButtons(customer.getPhone(), body,
                List.of(
                        new WhatsAppMessage.Button("pin_apply_yes", "Yes, apply it"),
                        new WhatsAppMessage.Button("pin_apply_no",  "No")
                ));
    }
}
