package com.tranche.bakery.order;

/** Where an order was raised. Records origin only — it never partitions carts or drafts. */
public enum OrderChannel {
    WHATSAPP,
    WEB
}
