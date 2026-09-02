package com.tranche.bakery.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides when a payment QR should charge a token (~₹1) amount instead of the real total.
 * Enabled globally via {@code bakery.payment.test-mode} (used by automated tests), or for a
 * whitelist of phone numbers via {@code bakery.payment.test-phones} (comma-separated) so specific
 * numbers can test the full flow on live production without moving real money.
 */
@Component
public class PaymentTestMode {

    private final boolean globalTestMode;
    private final Set<String> testPhones = new LinkedHashSet<>();

    public PaymentTestMode(
            @Value("${bakery.payment.test-mode:false}") boolean globalTestMode,
            @Value("${bakery.payment.test-phones:}") String testPhonesCsv) {
        this.globalTestMode = globalTestMode;
        if (testPhonesCsv != null) {
            for (String p : testPhonesCsv.split(",")) {
                String digits = p.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) testPhones.add(digits);
            }
        }
    }

    /** True when this phone should be charged the token test amount rather than the real total. */
    public boolean isTestPayment(String phone) {
        if (globalTestMode) return true;
        if (phone == null) return false;
        return testPhones.contains(phone.replaceAll("[^0-9]", ""));
    }

    /** The amount to charge on the QR: a token rupee amount for test phones, else the real amount. */
    public BigDecimal amountFor(String phone, BigDecimal realAmount) {
        return isTestPayment(phone) ? tokenAmount() : realAmount;
    }

    // ~₹1 with random paise so repeated test QRs stay distinct in UPI history.
    private BigDecimal tokenAmount() {
        return BigDecimal.valueOf(1.0 + (int) (Math.random() * 99) / 100.0).setScale(2, RoundingMode.HALF_UP);
    }
}
