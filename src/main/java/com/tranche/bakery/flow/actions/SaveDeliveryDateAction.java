package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.order.OrderStatus;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SaveDeliveryDateAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final WhatsAppClient whatsAppClient;

    private static final int MAX_PENDING_ORDERS = 3;

    @Override
    public String getName() { return "SAVE_DELIVERY_DATE"; }

    @Override
    @Transactional
    public void execute(ActionContext ctx) {
        String orderIdStr = ctx.contextValue("orderId");
        String dateStr    = ctx.contextValue("deliveryDate");
        if (orderIdStr == null || dateStr == null) return;

        Order draft = orderRepository.findById(Long.parseLong(orderIdStr)).orElse(null);
        if (draft == null || draft.getStatus() != OrderStatus.DRAFT) return;

        LocalDate deliveryDate = LocalDate.parse(dateStr);
        Long customerId = ctx.getCustomer().getId();

        // Case 1: customer already has a PENDING_CONFIRMATION order for this exact date → merge into it
        Order existing = orderRepository
                .findTopByCustomerIdAndStatusAndDeliveryDate(customerId, OrderStatus.PENDING_CONFIRMATION, deliveryDate)
                .orElse(null);
        if (existing != null) {
            orderService.mergeItems(draft, existing);
            orderService.cancel(draft);
            ctx.getConversation().getContext().put("orderId", existing.getId().toString());
            ctx.setRedirectState("ORDER_CONFIRM");
            log.info("Merged draft {} into existing order {} for date {} (customer {})",
                    draft.getId(), existing.getId(), deliveryDate, ctx.getCustomer().getPhone());
            return;
        }

        // Count current PENDING_CONFIRMATION orders
        List<Order> pending = orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION);

        // Case 2: max separate orders reached → block and cancel draft
        if (pending.size() >= MAX_PENDING_ORDERS) {
            orderService.cancel(draft);
            ctx.getConversation().getContext().remove("orderId");
            whatsAppClient.sendText(ctx.getCustomer().getPhone(),
                    "You already have " + MAX_PENDING_ORDERS + " orders awaiting payment. " +
                    "Please complete payment for an existing order before placing a new one.\n\n" +
                    "Send *hi* to return to the main menu.");
            ctx.setRedirectState("IDLE");
            log.info("Blocked new order for {} — max {} pending orders reached",
                    ctx.getCustomer().getPhone(), MAX_PENDING_ORDERS);
            return;
        }

        // Save delivery date on draft
        draft.setDeliveryDate(deliveryDate);
        orderRepository.save(draft);

        // Case 3: has other pending orders — warn about a separate order
        if (!pending.isEmpty()) {
            ctx.setRedirectState("ORDER_CONFIRM_SEPARATE");
            log.info("Draft {} set to {} — showing separate-order warning (customer {})",
                    draft.getId(), deliveryDate, ctx.getCustomer().getPhone());
            return;
        }

        // Case 4: no existing pending orders — normal flow continues to ADDRESS_GATE
        log.info("Delivery date {} saved for draft {} (customer {})",
                deliveryDate, draft.getId(), ctx.getCustomer().getPhone());
    }
}
