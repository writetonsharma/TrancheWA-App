package com.tranche.bakery.flow.actions;

import com.tranche.bakery.flow.ActionContext;
import com.tranche.bakery.flow.FlowAction;
import com.tranche.bakery.order.*;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CopyLastOrderAction implements FlowAction {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;
    private final WhatsAppClient whatsAppClient;

    @Override
    public String getName() { return "COPY_LAST_ORDER"; }

    @Override
    public void execute(ActionContext ctx) {
        Optional<Order> last = orderRepository.findTopByCustomerIdAndStatusInOrderByCreatedAtDesc(
                ctx.getCustomer().getId(),
                List.of(OrderStatus.CONFIRMED, OrderStatus.IN_BAKING, OrderStatus.COMPLETED));

        if (last.isEmpty()) {
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
            return;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrderId(last.get().getId());
        if (items.isEmpty()) {
            ctx.setRedirectState("ORDER_SELECT_CATEGORY");
            return;
        }

        Order draft = orderService.getOrCreateDraft(ctx.getCustomer(), ctx.getConversation());
        for (OrderItem item : items) {
            orderService.addItem(draft, item.getMenuItem().getId(), item.getQuantity());
        }
        ctx.getConversation().getContext().put("orderId", String.valueOf(draft.getId()));
        log.info("Copied {} items from last order to draft {} for customer {}",
                items.size(), draft.getId(), ctx.getCustomer().getPhone());
    }
}
