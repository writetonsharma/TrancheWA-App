package com.tranche.bakery.customer;

/**
 * Canonical phone form used to identify a customer: "91XXXXXXXXXX", the shape the WhatsApp
 * webhook stores. Every entry point must normalize through here — a second format would create
 * a duplicate Customer and silently detach their credits, pricing and order history.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 10 ? "91" + digits : digits;
    }
}
