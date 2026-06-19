package com.tranche.bakery.order;

import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;

    @Transactional
    public void cancelDraftIfExists(Customer customer) {
        orderRepository.findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .ifPresent(order -> {
                    order.setStatus(OrderStatus.CANCELLED);
                    orderRepository.save(order);
                });
    }

    @Transactional
    public Order getOrCreateDraft(Customer customer, WhatsappConversation conversation) {
        return orderRepository
                .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .orElseGet(() -> {
                    Order o = new Order();
                    o.setCustomer(customer);
                    o.setConversation(conversation);
                    o.setStatus(OrderStatus.DRAFT);
                    return orderRepository.save(o);
                });
    }

    @Transactional
    public void mergeItems(Order source, Order target) {
        List<OrderItem> sourceItems = orderItemRepository.findAllByOrderId(source.getId());
        List<OrderItem> targetItems = orderItemRepository.findAllByOrderId(target.getId());
        for (OrderItem si : sourceItems) {
            OrderItem existing = targetItems.stream()
                    .filter(ti -> ti.getMenuItem().getId().equals(si.getMenuItem().getId()))
                    .findFirst().orElse(null);
            if (existing != null) {
                int newQty = existing.getQuantity() + si.getQuantity();
                existing.setQuantity(newQty);
                existing.setSubtotal(existing.getUnitPrice().multiply(java.math.BigDecimal.valueOf(newQty)));
                orderItemRepository.save(existing);
            } else {
                OrderItem copy = new OrderItem();
                copy.setOrder(target);
                copy.setMenuItem(si.getMenuItem());
                copy.setQuantity(si.getQuantity());
                copy.setUnitPrice(si.getUnitPrice());
                copy.setSubtotal(si.getSubtotal());
                orderItemRepository.save(copy);
            }
        }
        recalculateTotal(target);
    }

    @Transactional
    public void addItem(Order order, Long menuItemId, int quantity) {
        MenuItem menuItem = menuItemRepository.findById(menuItemId)
                .orElseThrow(() -> new IllegalArgumentException("Menu item not found: " + menuItemId));

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setMenuItem(menuItem);
        item.setQuantity(quantity);
        item.setUnitPrice(menuItem.getPrice());
        item.setSubtotal(menuItem.getPrice().multiply(BigDecimal.valueOf(quantity)));
        orderItemRepository.save(item);

        recalculateTotal(order);
    }

    @Transactional
    public void confirm(Order order) {
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        orderRepository.save(order);
    }

    @Transactional
    public void cancel(Order order) {
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    public String formatSummary(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        if (items.isEmpty()) return "Your order is empty.";

        StringBuilder sb = new StringBuilder("🧾 *Your Order*\n\n");
        for (OrderItem item : items) {
            sb.append(String.format("• %s × %d — ₹%.0f\n",
                    item.getMenuItem().getName(),
                    item.getQuantity(),
                    item.getSubtotal()));
        }
        sb.append(String.format("\n*Total: ₹%.0f*", order.getTotalAmount()));

        boolean afterCutoff = java.time.LocalTime.now().getHour() >= 18;
        if (afterCutoff) {
            sb.append("\n\n_After 6 PM: this order will not be baked tomorrow morning. It will be scheduled for the following bake day._");
        }
        return sb.toString();
    }

    private void recalculateTotal(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        BigDecimal total = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);
        orderRepository.save(order);
    }
}
