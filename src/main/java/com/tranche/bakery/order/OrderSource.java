package com.tranche.bakery.order;

/** Where an order originated: retail (WhatsApp bot) or a manually-created commercial/bulk order. */
public enum OrderSource {
    RETAIL,
    COMMERCIAL
}
