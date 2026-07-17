package com.tranche.bakery.offer;

/** The pricing mechanic an offer implements. New mechanics are rare and require code + tests. */
public enum OfferKind {
    PERCENT_OFF,          // percentage off the cart subtotal
    FREE_DELIVERY_OVER,   // waive delivery when the discounted subtotal reaches a threshold
    FREE_ITEM_OVER        // add a complimentary item when the discounted subtotal reaches a threshold
}
