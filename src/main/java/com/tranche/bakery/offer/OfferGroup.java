package com.tranche.bakery.offer;

/**
 * Stacking group. Within a group only the single best-for-customer offer applies;
 * different groups stack with each other. This is what makes overlapping offers
 * deterministic and always customer-favourable.
 */
public enum OfferGroup {
    DISCOUNT,   // subtotal discounts (percent/amount off)
    DELIVERY,   // delivery perks (free delivery)
    GIFT        // complimentary items
}
