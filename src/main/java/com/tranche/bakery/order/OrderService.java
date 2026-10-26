package com.tranche.bakery.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tranche.bakery.conversation.WhatsappConversation;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.menu.MenuItemRepository;
import com.tranche.bakery.payment.Payment;
import com.tranche.bakery.payment.PaymentRepository;
import com.tranche.bakery.payment.PaymentStatus;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.payment.PaymentLinkRevocations;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MenuItemRepository menuItemRepository;
    private final CartPricingService cartPricingService;
    private final PaymentRepository paymentRepository;
    private final RazorpayService razorpayService;
    private final OrderNumberGenerator orderNumberGenerator;

    @Autowired private CapacityLock capacityLock;
    @Autowired private DeliveryRules deliveryRules;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PaymentLinkRevocations revocations;
    @PersistenceContext private EntityManager entityManager;

    @Value("${bakery.order.cutoff-hour}")
    private int cutoffHour;

    @Transactional
    public void cancelDraftIfExists(Customer customer) {
        orderRepository.findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .ifPresent(order -> {
                    if (lockDraftRow(order)) {
                        Order current = fresh(order);
                        requireMutable(current);
                        current.setStatus(OrderStatus.CANCELLED);
                        orderRepository.save(current);
                    }
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
     *
     * This is the right number to pair with DeliveryRules.remainingCapacity(): that already
     * counts PENDING_CONFIRMATION and beyond across all customers, and DRAFT is deliberately
     * excluded from it, so draft quantity is exactly what capacity checks still have to add.
     */
    public int currentDraftItemCount(Customer customer) {
        return orderRepository
                .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customer.getId(), OrderStatus.DRAFT)
                .map(o -> orderItemRepository.sumQuantityByOrderId(o.getId()))
                .orElse(0);
    }

    public void mergeItems(Order source, Order target) {
        mutate(() -> { mergeItemsInTransaction(source, target); return null; }, ignored -> target.getId());
    }

    private void mergeItemsInTransaction(Order source, Order target) {
        source = prepareMutation(source);
        target = prepareMutation(target);
        requireMutable(source);
        requireMutable(target);
        if (source.getId().equals(target.getId())) throw new IllegalStateException("INVALID_ORDER");
        if (source.getStatus() != OrderStatus.DRAFT || (target.getStatus() != OrderStatus.DRAFT
                && target.getStatus() != OrderStatus.PENDING_CONFIRMATION)) {
            throw new IllegalStateException("INVALID_ORDER");
        }
        List<OrderItem> sourceItems = freshItems(source);
        List<OrderItem> targetItems = freshItems(target);
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
        if (DeliveryRules.capacityStatuses().contains(target.getStatus())) validateReservation(target);
        recalculateTotal(target);
        // The target's total just changed, so a link issued for the old amount has to die now — not at
        // the next payment prompt, which the customer may never reach.
        revokePaymentLink(target);
    }

    public void addItem(Order order, Long menuItemId, int quantity) {
        mutate(() -> { addItemInTransaction(order, menuItemId, quantity); return null; }, ignored -> order.getId());
    }

    private void addItemInTransaction(Order order, Long menuItemId, int quantity) {
        order = prepareMutation(order);
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        MenuItem menuItem = menuItemRepository.findById(Objects.requireNonNull(menuItemId))
                .orElseThrow(() -> new IllegalArgumentException("Menu item not found: " + menuItemId));

        List<OrderItem> existingItems = freshItems(order);
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

        if (DeliveryRules.capacityStatuses().contains(order.getStatus())) validateReservation(order);
        recalculateTotal(order);
        if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION) revokePaymentLink(order);
    }

    /** Remove one line from the draft (only if it belongs to this order), then recompute totals. */
    public void removeItem(Order order, Long orderItemId) {
        mutate(() -> { removeItemInTransaction(order, orderItemId); return null; }, ignored -> order.getId());
    }

    private void removeItemInTransaction(Order order, Long orderItemId) {
        Order current = prepareMutation(order);
        orderItemRepository.findById(Objects.requireNonNull(orderItemId))
                .filter(it -> it.getOrder() != null && it.getOrder().getId().equals(current.getId()))
                .ifPresent(it -> {
                    orderItemRepository.delete(Objects.requireNonNull(it));
                    recalculateTotal(current);
                    if (current.getStatus() == OrderStatus.PENDING_CONFIRMATION) revokePaymentLink(current);
                });
    }

    @Transactional
    public void confirm(Order order) {
        capacityLock.acquire();
        order = freshLocked(order);
        requireMutable(order);
        if (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new CapacityReservationException("INVALID_ORDER");
        }
        validateReservation(order);
        if (order.getOrderNumber() == null) {
            order.setOrderNumber(orderNumberGenerator.generate(order.getId(), order.getCreatedAt()));
        }
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        orderRepository.save(order);
    }

    public void cancel(Order order) {
        mutate(() -> { cancelInTransaction(order); return null; }, ignored -> order.getId());
    }

    private void cancelInTransaction(Order order) {
        order = lockedForMutation(order);
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        revokePaymentLink(order);
    }

    public boolean cancelByIdForCustomer(Long orderId, Long customerId) {
        return mutate(() -> cancelByIdForCustomerInTransaction(orderId, customerId), ignored -> orderId);
    }

    private boolean cancelByIdForCustomerInTransaction(Long orderId, Long customerId) {
        Order order = orderRepository.findById(Objects.requireNonNull(orderId)).orElse(null);
        if (order == null) return false;
        if (order.getChannel() == OrderChannel.WEB) return false;
        if (!order.getCustomer().getId().equals(customerId)) return false;
        order = lockedForMutation(order);
        if (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.PENDING_CONFIRMATION) return false;
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        revokePaymentLink(order);
        return true;
    }

    /**
     * Kills the live gateway link for an order. Must run whenever the order is cancelled or its total
     * changes: a link that outlives what it was issued for can still be paid, and that money arrives
     * against an order it no longer matches.
     */
    public void revokePaymentLink(Order order) {
        mutate(() -> {
            capacityLock.acquire();
            fencePaymentLink(order);
            return null;
        }, ignored -> order.getId());
    }

    private void fencePaymentLink(Order order) {
        Payment payment = paymentRepository.findByOrder(order).orElse(null);
        if (payment != null) entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        if (payment == null || payment.getGatewayLinkId() == null
                || payment.getStatus() != PaymentStatus.PENDING) return;
        revocations.fence(payment);
    }

    private <T> T mutate(Supplier<T> work, Function<T, Long> affectedOrder) {
        // REQUIRED preserves the caller's atomic cancellation/cart/merge transaction. Only
        // its owner may dispatch after execute() has committed AND cleaned up resources.
        T result = new TransactionTemplate(transactionManager).execute(status -> work.get());
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive()) {
            revocations.retryForOrder(affectedOrder.apply(result));
        }
        return result;
    }

    /** What should happen to a finished draft once its delivery date is known. */
    public enum CartResolution { MERGED, SEPARATE_ORDER, NEW_ORDER }

    /** Where a draft's items belong, and the order that now owns them. */
    public record CartDecision(CartResolution resolution, Order order) {}

    /**
     * Resolves a completed draft against the customer's existing unpaid orders. A same-date unpaid
     * order absorbs the draft, so a customer only ever has one order per delivery day no matter which
     * channel they built it on. Other dates stay separate — they are different bakes.
     */
    public CartDecision resolveCart(Customer customer, Order draft, LocalDate date) {
        return mutate(() -> resolveCartInTransaction(customer, draft, date), decision -> decision.order().getId());
    }

    private CartDecision resolveCartInTransaction(Customer customer, Order draft, LocalDate date) {
        Order sameDay = orderRepository
            .findAllByCustomerIdAndStatus(customer.getId(), OrderStatus.PENDING_CONFIRMATION).stream()
            .filter(o -> o.getChannel() != OrderChannel.WEB)
            .filter(o -> date.equals(o.getDeliveryDate()))
            .findFirst()
                .orElse(null);

        if (sameDay != null) {
            capacityLock.acquire();
            mergeItems(draft, sameDay);
            cancel(draft);
            return new CartDecision(CartResolution.MERGED, sameDay);
        }

        boolean unpaidElsewhere = !orderRepository
                .findAllByCustomerIdAndStatus(customer.getId(), OrderStatus.PENDING_CONFIRMATION).isEmpty();
        return new CartDecision(
                unpaidElsewhere ? CartResolution.SEPARATE_ORDER : CartResolution.NEW_ORDER, draft);
    }

    public String formatSummary(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        if (items.isEmpty()) return "Your order is empty.";

        Customer customer = order.getCustomer();
        boolean hasOverride = customer != null && customer.hasActiveOverride();

        StringBuilder sb = new StringBuilder("🧾 *Your Order*\n\n");
        for (OrderItem item : items) {
            BigDecimal unit = hasOverride && customer != null ? customer.unitPriceFor(itemNameOf(item), categoryNameOf(item)) : null;
            BigDecimal special = unit != null ? unit.multiply(BigDecimal.valueOf(item.getQuantity())) : null;
            if (special != null && special.compareTo(item.getSubtotal()) < 0) {
                // F&F flat price below list — strike the list price so the per-item saving is visible.
                sb.append(String.format("• %s × %d — ~₹%.0f~ *₹%.0f*\n",
                        item.getMenuItem().getName(), item.getQuantity(), item.getSubtotal(), special));
            } else {
                BigDecimal shown = special != null ? special : item.getSubtotal();
                sb.append(String.format("• %s × %d — ₹%.0f\n",
                        item.getMenuItem().getName(), item.getQuantity(), shown));
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

        if (order.getCreditApplied() != null && order.getCreditApplied().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("• Credit applied — −₹%.0f\n", order.getCreditApplied()));
        }

        sb.append(String.format("\n*Total: ₹%.0f*", order.getTotalAmount()));

        BigDecimal savings = savingsFor(order);
        if (savings.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("\n💰 *You saved ₹%.0f* %s 🎉", savings,
                    hasOverride ? "with your Friends & Family pricing" : "with today's offer"));
        }

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

        return sb.toString();
    }

    public boolean hasPendingPayment(Long customerId) {
        return findAwaitingPayment(customerId).isPresent();
    }

    /** The order the customer is currently being asked to pay for, if any. */
    public Optional<Order> findAwaitingPayment(Long customerId) {
        return orderRepository.findAllByCustomerIdAndStatus(customerId, OrderStatus.PENDING_CONFIRMATION)
                .stream().filter(o -> o.getChannel() != OrderChannel.WEB).findFirst();
    }

    /** An order the customer may still pay for: theirs, and awaiting payment. */
    public Optional<Order> findPayableForCustomer(Long orderId, Long customerId) {
        return orderRepository.findById(Objects.requireNonNull(orderId))
            .filter(o -> o.getChannel() != OrderChannel.WEB)
                .filter(o -> o.getCustomer().getId().equals(customerId))
                .filter(o -> o.getStatus() == OrderStatus.PENDING_CONFIRMATION);
    }

    public Optional<Order> findRevivableLatePayment(Long customerId) {
        LocalDate today = LocalDate.now();
        return orderRepository
                .findAllByCustomerIdAndStatusAndCutoffCancelledTrueOrderByUpdatedAtDesc(customerId, OrderStatus.CANCELLED)
                .stream()
                .filter(o -> o.getChannel() != OrderChannel.WEB)
                .filter(o -> o.getDeliveryDate() != null)
                .filter(o -> o.getUpdatedAt() != null && o.getUpdatedAt().toLocalDate().equals(today))
                .findFirst();
    }

    /** Recompute totals for an order (e.g. after its delivery date is set). */
    @Transactional
    public void recalculate(Order order) {
        requireMutable(order);
        // Direct pricing-only unit fixtures construct this service without a persistence context.
        // Every Spring-managed instance has one, and must coordinate even a stale DRAFT input.
        if (entityManager != null) {
            LocalDate requestedDate = order.getDeliveryDate();
            boolean draftInput = order.getStatus() == OrderStatus.DRAFT;
            order = prepareMutation(order);
            if (draftInput && order.getStatus() == OrderStatus.DRAFT) order.setDeliveryDate(requestedDate);
        }
        if (DeliveryRules.capacityStatuses().contains(order.getStatus())) {
            validateReservation(order);
        }
        recalculateTotal(order);
    }

    /** Re-open a legacy cutoff cancellation only after freshly reserving its replacement date. */
    @Transactional
    public Order reviveCutoffCancelled(Long orderId, Long customerId, LocalDate date) {
        capacityLock.acquire();
        Order order = orderRepository.findById(Objects.requireNonNull(orderId)).orElseThrow(() -> new IllegalStateException("INVALID_ORDER"));
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        requireMutable(order);
        if (!order.getCustomer().getId().equals(customerId) || order.getStatus() != OrderStatus.CANCELLED
                || !order.isCutoffCancelled()) throw new IllegalStateException("INVALID_ORDER");
        order.setDeliveryDate(date);
        validateReservation(order);
        order.setCutoffCancelled(false);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        return orderRepository.save(order);
    }

    private Order fresh(Order order) {
        Order current = entityManager.find(Order.class, order.getId());
        if (current == null) throw new IllegalStateException("INVALID_ORDER");
        entityManager.refresh(current);
        return current;
    }

    private Order freshLocked(Order order) {
        Order current = entityManager.find(Order.class, order.getId());
        if (current == null) throw new IllegalStateException("INVALID_ORDER");
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        return current;
    }

    private Order prepareMutation(Order order) {
        Order current = lockedForMutation(order);
        if (current.getStatus() != OrderStatus.DRAFT && current.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("INVALID_ORDER");
        }
        return current;
    }

    private boolean lockDraftRow(Order order) {
        boolean locked;
        try {
            locked = !jdbcTemplate.queryForList(
                    "SELECT id FROM orders WHERE id = ? AND status = 'DRAFT' FOR UPDATE NOWAIT",
                    Long.class, order.getId()).isEmpty();
        } catch (DataAccessException ex) {
            throw CapacityLock.translateLockFailure(ex);
        }
        if (locked) capacityLock.noteDraftRowLock();
        return locked;
    }

    private Order lockedForMutation(Order order) {
        // Draft-only edits take only their row, NOWAIT: a reservation can wait for an editing
        // draft, but an editor never holds an order row while waiting for the global mutex.
        boolean draftLocked = lockDraftRow(order);
        Order current;
        if (draftLocked) {
            current = fresh(order);
        } else {
            capacityLock.acquire();
            current = freshLocked(order);
        }
        requireMutable(current);
        return current;
    }

    private void requireMutable(Order order) {
        if (order.getChannel() == OrderChannel.WEB) throw new IllegalStateException("WEB_ORDER_IMMUTABLE");
    }

    private void validateReservation(Order order) {
        // Flush our mutations before refreshing lines: pre-confirm may have loaded these entities
        // before waiting for the lock, and a JPQL query alone does not refresh the first-level cache.
        var items = freshItems(order);
        if (items.isEmpty() || items.stream().anyMatch(i -> i.getQuantity() <= 0 || !i.getMenuItem().isActive())) {
            throw new CapacityReservationException("INVALID_ORDER");
        }
        LocalDate date = order.getDeliveryDate();
        boolean friendsAndFamily = order.getCustomer().isFriendsAndFamily();
        if (date == null || items.stream().anyMatch(i ->
                !deliveryRules.itemDeliverableOn(i.getMenuItem().getName(), date, friendsAndFamily))) {
            throw new CapacityReservationException("INVALID_DELIVERY_DATE");
        }
        long quantity = items.stream().mapToLong(i -> i.getQuantity()).sum();
        long others = orderItemRepository.sumBookedQuantityExcludingOrder(
                date, DeliveryRules.capacityStatuses(), order.getId());
        if (others + quantity > deliveryRules.getDailyCapacity()) throw new CapacityReservationException("FULLY_BOOKED");
    }

    private List<OrderItem> freshItems(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        for (OrderItem item : items) {
            entityManager.refresh(item);
            entityManager.refresh(item.getMenuItem());
        }
        return items;
    }

    private String itemNameOf(OrderItem item) {
        return item.getMenuItem() != null ? item.getMenuItem().getName() : null;
    }

    private String categoryNameOf(OrderItem item) {
        return item.getMenuItem() != null && item.getMenuItem().getCategory() != null
                ? item.getMenuItem().getCategory().getName()
                : null;
    }

    // Total the customer saved on items (F&F special-rate or offers/batch), excluding delivery.
    public BigDecimal savingsFor(Order order) {
        return nz(order.getDiscountAmount()).add(nz(order.getBatchDiscountAmount()));
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private void recalculateTotal(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        CartPricingService.Price price = cartPricingService.price(items.stream()
                .map(it -> new CartPricingService.Line(it.getMenuItem(), it.getQuantity(), it.getSubtotal()))
                .toList(), order.getCustomer(), order.getDeliveryDate(), order.getId());
        order.setDeliveryCharge(price.deliveryCharge());
        order.setDiscountAmount(price.discountAmount());
        order.setDiscountLabel(price.discountLabel());
        order.setGiftLabel(price.giftLabel());
        order.setBatchDiscountAmount(price.batchDiscountAmount());
        order.setBatchDiscountLabel(price.batchDiscountLabel());
        order.setCreditApplied(price.creditApplied());
        order.setTotalAmount(price.totalAmount());
        orderRepository.save(order);
    }
}
