package com.tranche.bakery.offer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Output of the PromotionEngine.
 *
 * itemsTotal     - final items total after the best discount (excludes delivery)
 * discountAmount - listSubtotal - itemsTotal (>= 0)
 * discountLabel  - label of the winning discount (or special-rate note), null if none
 * freeDelivery   - whether delivery should be waived
 * deliveryLabel  - label of the delivery offer that waived it, null if none
 * giftLabel      - complimentary item line, null if none
 * appliedLabels  - all offer labels that applied, for logging/summary
 */
public record PromoResult(
        BigDecimal itemsTotal,
        BigDecimal discountAmount,
        String discountLabel,
        boolean freeDelivery,
        String deliveryLabel,
        String giftLabel,
        List<String> appliedLabels
) {}
