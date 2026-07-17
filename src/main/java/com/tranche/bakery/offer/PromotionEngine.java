package com.tranche.bakery.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Deterministic, config-driven promotions engine.
 *
 * Pipeline (fixed stages so overlaps never depend on ordering):
 *   1. DISCOUNT group  -> best percentage off the list subtotal; then the customer
 *                         keeps whichever is cheaper, this vs any bespoke admin rate.
 *   2. DELIVERY group  -> threshold evaluated against the DISCOUNTED subtotal.
 *   3. GIFT group      -> threshold evaluated against the DISCOUNTED subtotal.
 *
 * Within each group only one offer wins (best-for-customer); groups stack. Adding,
 * rotating or pausing an offer is data-only via the admin Offers page.
 */
@Component
@RequiredArgsConstructor
public class PromotionEngine {

    private final OfferRepository offerRepository;

    public PromoResult evaluate(PromoContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        List<Offer> live = offerRepository.findAllByOrderByDisplayOrderAsc().stream()
                .filter(o -> o.isLiveAt(now))
                .toList();

        BigDecimal listSubtotal = ctx.listSubtotal() == null ? BigDecimal.ZERO : ctx.listSubtotal();
        List<String> applied = new ArrayList<>();

        // --- Stage 1: DISCOUNT group (best percentage off) ---
        BigDecimal bestDiscount = BigDecimal.ZERO;
        String discountLabel = null;
        int bestPriority = Integer.MIN_VALUE;
        for (Offer o : live) {
            if (o.getStackGroup() != OfferGroup.DISCOUNT || o.getKind() != OfferKind.PERCENT_OFF) continue;
            if (o.getPercent() == null) continue;
            BigDecimal d = listSubtotal.multiply(o.getPercent())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            if (d.compareTo(bestDiscount) > 0
                    || (d.compareTo(bestDiscount) == 0 && o.getPriority() > bestPriority && d.signum() > 0)) {
                bestDiscount = d;
                discountLabel = o.getLabel();
                bestPriority = o.getPriority();
            }
        }

        BigDecimal itemsTotal = listSubtotal.subtract(bestDiscount);

        // Bespoke admin per-customer rate: customer keeps whichever is cheaper.
        if (ctx.overrideTotal() != null && ctx.overrideTotal().compareTo(itemsTotal) < 0) {
            itemsTotal = ctx.overrideTotal();
            discountLabel = "Special rate";
        }
        if (itemsTotal.signum() < 0) itemsTotal = BigDecimal.ZERO;

        BigDecimal discountAmount = listSubtotal.subtract(itemsTotal);
        if (discountLabel != null && discountAmount.signum() > 0) applied.add(discountLabel);

        // --- Stage 2: DELIVERY group (threshold on discounted subtotal) ---
        boolean freeDelivery = ctx.customerFreeDelivery();
        String deliveryLabel = null;
        for (Offer o : live) {
            if (o.getStackGroup() != OfferGroup.DELIVERY || o.getKind() != OfferKind.FREE_DELIVERY_OVER) continue;
            if (o.getThresholdAmount() == null) continue;
            if (itemsTotal.compareTo(o.getThresholdAmount()) >= 0) {
                freeDelivery = true;
                deliveryLabel = o.getLabel();
                applied.add(o.getLabel());
                break; // one delivery perk is enough
            }
        }

        // --- Stage 3: GIFT group (threshold on discounted subtotal) ---
        String giftLabel = null;
        int giftPriority = Integer.MIN_VALUE;
        for (Offer o : live) {
            if (o.getStackGroup() != OfferGroup.GIFT || o.getKind() != OfferKind.FREE_ITEM_OVER) continue;
            if (o.getThresholdAmount() == null) continue;
            if (itemsTotal.compareTo(o.getThresholdAmount()) >= 0 && o.getPriority() > giftPriority) {
                giftLabel = (o.getGiftLabel() != null && !o.getGiftLabel().isBlank())
                        ? o.getGiftLabel() : o.getLabel();
                giftPriority = o.getPriority();
            }
        }
        if (giftLabel != null) applied.add(giftLabel);

        return new PromoResult(itemsTotal, discountAmount, discountLabel,
                freeDelivery, deliveryLabel, giftLabel, applied);
    }
}
