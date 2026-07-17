package com.tranche.bakery;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tranche.bakery.menu.MenuItem;
import com.tranche.bakery.offer.Offer;
import com.tranche.bakery.offer.OfferRepository;
import com.tranche.bakery.order.Order;
import com.tranche.bakery.order.OrderService;

/**
 * Golden scenarios for the config-driven PromotionEngine, exercised through
 * OrderService.recalculateTotal against the migration-seeded launch offers
 * (launch-20, free-delivery-550, sweet-roll-450) and the ~50 test delivery fee.
 *
 * Expectations are computed from the real seeded item price so the tests stay
 * correct if list prices change.
 */
class PromotionFlowTest extends FlowScenarioBase {

    @Autowired private OrderService orderService;
    @Autowired private OfferRepository offerRepository;

    private static final BigDecimal DELIVERY_FEE = new BigDecimal("50");

    /**
     * Offers live outside the truncate list in FlowScenarioBase, so tests that
     * toggle/date-shift them would leak into siblings. Restore the seeded
     * baseline (all three active, launch-20 always-live) before each test.
     */
    @BeforeEach
    void resetOffers() {
        restore("launch-20");
        restore("free-delivery-550");
        restore("sweet-roll-450");
    }

    private void restore(String code) {
        Offer o = offerRepository.findByCode(code).orElseThrow();
        o.setActive(true);
        o.setStartsAt(null);
        o.setEndsAt(null);
        offerRepository.save(o);
    }

    private MenuItem anyItem() {
        return itemRepository.findAll().stream()
                .filter(MenuItem::isActive)
                .min(java.util.Comparator.comparing(MenuItem::getPrice))
                .orElseThrow();
    }

    private Order draftWith(MenuItem item, int qty) {
        Order order = orderService.getOrCreateDraft(customer, conversation);
        orderService.addItem(order, item.getId(), qty);
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private BigDecimal expectedDiscount(BigDecimal subtotal) {
        return subtotal.multiply(new BigDecimal("20"))
                .divide(new BigDecimal("100"), 0, java.math.RoundingMode.HALF_UP);
    }

    // 1. Below all thresholds: 20% off applies; delivery charged; no gift.
    @Test
    void belowThresholds_discountOnly() {
        MenuItem item = anyItem();
        Order order = draftWith(item, 1);

        BigDecimal subtotal = item.getPrice();
        BigDecimal discounted = subtotal.subtract(expectedDiscount(subtotal));
        // ensure this scenario really is below 450
        assertThat(discounted).isLessThan(new BigDecimal("450"));

        assertThat(order.getDiscountAmount()).isEqualByComparingTo(expectedDiscount(subtotal));
        assertThat(order.getGiftLabel()).isNull();
        assertThat(order.getDeliveryCharge()).isEqualByComparingTo(DELIVERY_FEE);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(discounted.add(DELIVERY_FEE));
    }

    // 2. Discounted subtotal in [450, 550): free gift, delivery still charged.
    @Test
    void midThreshold_giftbutDeliveryCharged() {
        int[] pick = itemAndQtyForDiscountedRange(450, 550);
        MenuItem item = itemRepository.findById((long) pick[0]).orElseThrow();
        int qty = pick[1];
        Order order = draftWith(item, qty);

        BigDecimal subtotal = item.getPrice().multiply(BigDecimal.valueOf(qty));
        BigDecimal discounted = subtotal.subtract(expectedDiscount(subtotal));
        assertThat(discounted).isGreaterThanOrEqualTo(new BigDecimal("450"));
        assertThat(discounted).isLessThan(new BigDecimal("550"));

        assertThat(order.getGiftLabel()).isNotBlank();
        assertThat(order.getDeliveryCharge()).isEqualByComparingTo(DELIVERY_FEE);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(discounted.add(DELIVERY_FEE));
    }

    // 3. Discounted subtotal >= 550: gift AND free delivery stack.
    @Test
    void highThreshold_giftAndFreeDelivery() {
        int[] pick = itemAndQtyForDiscountedAtLeast(550);
        MenuItem item = itemRepository.findById((long) pick[0]).orElseThrow();
        int qty = pick[1];
        Order order = draftWith(item, qty);

        BigDecimal subtotal = item.getPrice().multiply(BigDecimal.valueOf(qty));
        BigDecimal discounted = subtotal.subtract(expectedDiscount(subtotal));
        assertThat(discounted).isGreaterThanOrEqualTo(new BigDecimal("550"));

        assertThat(order.getGiftLabel()).isNotBlank();
        assertThat(order.getDeliveryCharge()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(discounted);
    }

    // 4. Deactivated gift offer: no gift even above its threshold.
    @Test
    void inactiveGiftOffer_noGift() {
        Offer gift = offerRepository.findByCode("sweet-roll-450").orElseThrow();
        gift.setActive(false);
        offerRepository.save(gift);

        int[] pick = itemAndQtyForDiscountedAtLeast(550);
        MenuItem item = itemRepository.findById((long) pick[0]).orElseThrow();
        Order order = draftWith(item, pick[1]);

        assertThat(order.getGiftLabel()).isNull();
        // free delivery still applies (separate group)
        assertThat(order.getDeliveryCharge()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // 5. Admin override cheaper than 20% off: customer keeps the cheaper rate.
    @Test
    void adminOverride_takesCheaperRate() {
        customer.setPricingOverride(new BigDecimal("1"));
        customer.setOverrideExpiresAt(LocalDateTime.now().plusDays(1));
        customer = customerRepository.save(customer);

        MenuItem item = anyItem();
        Order order = draftWith(item, 2);

        // override total = 1 * qty(2) = 2, far below any discounted list price
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("2").add(DELIVERY_FEE));
        assertThat(order.getDiscountLabel()).isEqualTo("Special rate");
    }

    // 6. Offer not yet started (future window): no discount applied.
    @Test
    void futureOffer_notApplied() {
        Offer discount = offerRepository.findByCode("launch-20").orElseThrow();
        discount.setStartsAt(LocalDateTime.now().plusDays(2));
        offerRepository.save(discount);

        MenuItem item = anyItem();
        Order order = draftWith(item, 1);

        assertThat(order.getDiscountAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(item.getPrice().add(DELIVERY_FEE));
    }

    // --- helpers: find a quantity whose 20%-discounted subtotal lands in a range ---

    private int[] itemAndQtyForDiscountedRange(int lo, int hi) {
        for (MenuItem it : itemRepository.findAll()) {
            if (!it.isActive()) continue;
            for (int q = 1; q <= 20; q++) {
                BigDecimal sub = it.getPrice().multiply(BigDecimal.valueOf(q));
                BigDecimal disc = sub.subtract(expectedDiscount(sub));
                if (disc.compareTo(BigDecimal.valueOf(lo)) >= 0 && disc.compareTo(BigDecimal.valueOf(hi)) < 0)
                    return new int[]{ it.getId().intValue(), q };
            }
        }
        throw new IllegalStateException("No item/qty lands discounted in [" + lo + "," + hi + ")");
    }

    private int[] itemAndQtyForDiscountedAtLeast(int lo) {
        for (MenuItem it : itemRepository.findAll()) {
            if (!it.isActive()) continue;
            for (int q = 1; q <= 20; q++) {
                BigDecimal sub = it.getPrice().multiply(BigDecimal.valueOf(q));
                BigDecimal disc = sub.subtract(expectedDiscount(sub));
                if (disc.compareTo(BigDecimal.valueOf(lo)) >= 0)
                    return new int[]{ it.getId().intValue(), q };
            }
        }
        throw new IllegalStateException("No item/qty reaches discounted " + lo);
    }
}
