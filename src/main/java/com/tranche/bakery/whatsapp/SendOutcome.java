package com.tranche.bakery.whatsapp;

/** Result of a WhatsApp send, so callers can fall back to a template when the 24h window is closed. */
public enum SendOutcome {
    SENT,
    WINDOW_CLOSED,
    FAILED
}
