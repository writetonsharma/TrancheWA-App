package com.tranche.bakery.offer;

import java.math.BigDecimal;

/**
 * Input to the PromotionEngine for a single order.
 *
 * listSubtotal        - sum of line subtotals at list price
 * overrideTotal       - bespoke admin per-customer total, or null if none active
 * customerFreeDelivery- admin free-delivery flag on the customer
 * completedOrderCount - customer's delivered-order count (loyalty-ready; unused by
 *                       the current cart-scoped offers, wired so loyalty mechanics
 *                       can be added later with no pipeline changes)
 */
public record PromoContext(
        BigDecimal listSubtotal,
        BigDecimal overrideTotal,
        boolean customerFreeDelivery,
        int completedOrderCount
) {}
