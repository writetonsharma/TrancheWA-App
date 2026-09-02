package com.tranche.bakery.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PaymentTestModeTest {

    @Test
    void whitelistedPhoneGetsTokenAmount() {
        PaymentTestMode mode = new PaymentTestMode(false, "919000000001, 919811843373");

        assertThat(mode.isTestPayment("919000000001")).isTrue();
        assertThat(mode.amountFor("919000000001", new BigDecimal("258")))
                .isLessThanOrEqualTo(new BigDecimal("2.00"));
    }

    @Test
    void nonWhitelistedPhonePaysRealAmount() {
        PaymentTestMode mode = new PaymentTestMode(false, "919000000001");

        assertThat(mode.isTestPayment("919999999999")).isFalse();
        assertThat(mode.amountFor("919999999999", new BigDecimal("258")))
                .isEqualByComparingTo("258");
    }

    @Test
    void globalTestModeChargesEveryoneTheToken() {
        PaymentTestMode mode = new PaymentTestMode(true, "");

        assertThat(mode.isTestPayment("919999999999")).isTrue();
    }

    @Test
    void phoneMatchingIgnoresFormatting() {
        PaymentTestMode mode = new PaymentTestMode(false, "+91 90000-00001");

        assertThat(mode.isTestPayment("919000000001")).isTrue();
    }
}
