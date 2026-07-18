package com.tranche.bakery.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.offer.BatchDiscountService;
import com.tranche.bakery.offer.PromoContext;
import com.tranche.bakery.offer.PromoResult;
import com.tranche.bakery.offer.PromotionEngine;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final PromotionEngine promotionEngine;
    private final BatchDiscountService batchDiscountService;

    @Value("${bakery.order.delivery-charge:50}")
    private BigDecimal deliveryCharge;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

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

    /**
     * Total item quantity in the customer's current DRAFT cart, or 0 if none exists.
     * Read-only: does not create a draft.
     */
    public int currentDraftItemCount(Customer customer) {
        return orderRepository
                .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .map(o -> orderItemRepository.sumQuantityByOrderId(o.getId()))
                .orElse(0);
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

        List<OrderItem> existingItems = orderItemRepository.findAllByOrderId(order.getId());
        OrderItem existing = existingItems.stream()
                .filter(i -> i.getMenuItem().getId().equals(menuItemId))
                .findFirst().orElse(null);

        if (existing != null) {
            int newQty = existing.getQuantity() + quantity;
            existing.setQuantity(newQty);
            existing.setSubtotal(existing.getUnitPrice().multiply(BigDecimal.valueOf(newQty)));
            orderItemRepository.save(existing);
        } else {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setMenuItem(menuItem);
            item.setQuantity(quantity);
            item.setUnitPrice(menuItem.getPrice());
            item.setSubtotal(menuItem.getPrice().multiply(BigDecimal.valueOf(quantity)));
            orderItemRepository.save(item);
        }

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

    @Transactional
    public boolean cancelByIdForCustomer(Long orderId, Long customerId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return false;
        if (!order.getCustomer().getId().equals(customerId)) return false;
        if (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.PENDING_CONFIRMATION) return false;
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        return true;
    }

    public String formatSummary(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        if (items.isEmpty()) return "Your order is empty.";

        Customer customer = order.getCustomer();
        boolean hasOverride = customer != null && customer.hasActiveOverride();

        StringBuilder sb = new StringBuilder("🧾 *Your Order*\n\n");
        for (OrderItem item : items) {
            if (hasOverride) {
                BigDecimal discountedSubtotal = customer.getPricingOverride()
                        .multiply(BigDecimal.valueOf(item.getQuantity()));
                sb.append(String.format("• %s × %d — ₹%.0f _(special rate)_\n",
                        item.getMenuItem().getName(),
                        item.getQuantity(),
                        discountedSubtotal));
            } else {
                sb.append(String.format("• %s × %d — ₹%.0f\n",
                        item.getMenuItem().getName(),
                        item.getQuantity(),
                        item.getSubtotal()));
            }
        }

        if (!hasOverride && order.getDiscountAmount() != null
                && order.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            String label = order.getDiscountLabel() != null ? order.getDiscountLabel() : "Discount";
            sb.append(String.format("• %s — −₹%.0f\n", label, order.getDiscountAmount()));
        }

        if (!hasOverride && order.getBatchDiscountAmount() != null
                && order.getBatchDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
            String label = order.getBatchDiscountLabel() != null ? order.getBatchDiscountLabel() : "Batch discount";
            sb.append(String.format("\u2022 \uD83D\uDD25 %s \u2014 \u2212\u20B9%.0f\n", label, order.getBatchDiscountAmount()));
        }

        if (order.getDeliveryCharge() != null && order.getDeliveryCharge().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("• Delivery — ₹%.0f\n", order.getDeliveryCharge()));
        } else if (order.getFulfillmentType() == FulfillmentType.DELIVERY) {
            sb.append("• Delivery — _Free_ ✨\n");
        }

        if (order.getGiftLabel() != null && !order.getGiftLabel().isBlank()) {
            sb.append(String.format("• %s — _Free_ ✨\n", order.getGiftLabel()));
        }

        sb.append(String.format("\n*Total: ₹%.0f*", order.getTotalAmount()));

        if (order.getDeliveryPreference() != null) {
            String prefLabel = switch (order.getDeliveryPreference()) {
                case "GATE" -> "At apartment gate";
                case "DOOR" -> "Leave at door";
                case "IN_PERSON" -> "Deliver in person";
                default -> order.getDeliveryPreference();
            };
            sb.append("\n📦 Delivery: ").append(prefLabel);
        }

        if (order.getLoafPreference() != null) {
            String loafLabel = switch (order.getLoafPreference()) {
                case "SLICED" -> "Sliced";
                case "WHOLE" -> "Whole";
                default -> order.getLoafPreference();
            };
            sb.append("\n🍞 Loaves: ").append(loafLabel);
        }

        boolean afterCutoff = java.time.LocalTime.now().getHour() >= cutoffHour;
        if (afterCutoff) {
            sb.append("\n\n_After 5 PM: this order will not be baked tomorrow morning. It will be scheduled for the following bake day._");
        }
        return sb.toString();
    }

    public boolean hasPendingPayment(Long customerId) {
        return !orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION).isEmpty();
    }

    public Optional<Order> findRevivableLatePayment(Long customerId) {
        LocalDate today = LocalDate.now();
        return orderRepository
                .findAllByCustomerIdAndStatusAndCutoffCancelledTrueOrderByUpdatedAtDesc(customerId, OrderStatus.CANCELLED)
                .stream()
                .filter(o -> o.getDeliveryDate() != null)
                .filter(o -> o.getUpdatedAt() != null && o.getUpdatedAt().toLocalDate().equals(today))
                .findFirst();
    }

    /** Recompute totals for an order (e.g. after its delivery date is set). */
    @Transactional
    public void recalculate(Order order) {
        recalculateTotal(order);
    }

    private void recalculateTotal(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());

        BigDecimal listSubtotal = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Customer customer = order.getCustomer();
        BigDecimal overrideTotal = null;
        boolean freeDeliveryFlag = false;
        int completedOrders = 0;
        if (customer != null) {
            if (customer.hasActiveOverride()) {
                int totalQty = items.stream().mapToInt(OrderItem::getQuantity).sum();
                overrideTotal = customer.getPricingOverride().multiply(BigDecimal.valueOf(totalQty));
            }
            freeDeliveryFlag = customer.isFreeDelivery();
            completedOrders = (int) orderRepository.countByCustomerIdAndStatus(
                    customer.getId(), OrderStatus.COMPLETED);
        }

        PromoResult promo = promotionEngine.evaluate(
                new PromoContext(listSubtotal, overrideTotal, freeDeliveryFlag, completedOrders));

        BigDecimal fee = promo.freeDelivery() ? BigDecimal.ZERO : deliveryCharge;
        order.setDeliveryCharge(fee);
        order.setDiscountAmount(promo.discountAmount());
        order.setDiscountLabel(promo.discountLabel());
        order.setGiftLabel(promo.giftLabel());

        // Dynamic batch discount: once the delivery date is known, items whose booked
        // demand for that day has reached a band threshold earn an extra percentage
        // off their ALREADY-discounted line price (stacks on top of the launch offer).
        BigDecimal itemsTotal = promo.itemsTotal();
        BigDecimal batchDiscount = BigDecimal.ZERO;
        String batchLabel = null;
        boolean overrideActive = customer != null && customer.hasActiveOverride();
        if (order.getDeliveryDate() != null && !overrideActive
                && listSubtotal.signum() > 0) {
            BigDecimal discountedRatio = itemsTotal.divide(listSubtotal, 10, java.math.RoundingMode.HALF_UP);
            for (OrderItem it : items) {
                BigDecimal pct = batchDiscountService.extraPercentFor(
                        it.getMenuItem().getId(), it.getMenuItem().getPrice(), order.getDeliveryDate());
                if (pct == null) continue;
                BigDecimal discountedLine = it.getSubtotal().multiply(discountedRatio);
                batchDiscount = batchDiscount.add(
                        discountedLine.multiply(pct).divide(BigDecimal.valueOf(100), 10, java.math.RoundingMode.HALF_UP));
                batchLabel = "Batch discount";
            }
            batchDiscount = batchDiscount.setScale(0, java.math.RoundingMode.HALF_UP);
        }
        if (batchDiscount.signum() > 0) {
            itemsTotal = itemsTotal.subtract(batchDiscount);
            if (itemsTotal.signum() < 0) itemsTotal = BigDecimal.ZERO;
        } else {
            batchDiscount = BigDecimal.ZERO;
            batchLabel = null;
        }
        order.setBatchDiscountAmount(batchDiscount);
        order.setBatchDiscountLabel(batchLabel);

        order.setTotalAmount(itemsTotal.add(fee));
        orderRepository.save(order);
    }
}
