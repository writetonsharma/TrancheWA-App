package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AuthCryptoTest {
    static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]); // test only
    static WebAuthSettings settings() {
        return new WebAuthSettings(true, true, true, SECRET, "web_login", "en", "https://tranchebakery.in");
    }
    @Test void strictCanonicalPhone() {
        assertThat(AuthCrypto.phone("+919811843373")).isEqualTo("919811843373");
        assertThat(AuthCrypto.phone("919811843373")).isEqualTo("919811843373");
        for (String invalid : new String[]{"", " 919811843373", "91 9811843373", "00919811843373", "+0919811843373", "123", "<script>"})
            assertThat(AuthCrypto.phone(invalid)).isNull();
    }
    @Test void tokensAndCodeAreRandomAndHashesArePurposeBound() {
        AuthCrypto crypto = new AuthCrypto(settings());
        var tokens = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String token = crypto.token();
            assertThat(AuthCrypto.validToken(token)).isTrue();
            assertThat(tokens.add(token)).isTrue();
            assertThat(crypto.code()).matches("[0-9]{6}");
        }
        assertThat(crypto.hash("session", "value")).hasSize(64).isNotEqualTo(crypto.hash("browser", "value"));
        assertThat(crypto.matches(crypto.hash("otp", "code"), crypto.hash("otp", "code"))).isTrue();
    }
    @Test void missingWeakSecretAndWildcardOriginsFailClosed() {
        assertThatThrownBy(() -> new WebAuthSettings(true, true, true, "", "login", "en", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebAuthSettings(true, true, true, SECRET, "login", "en", "https://*.example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new WebAuthSettings(false, false, true, "", "", "en", "").canSend()).isFalse();
        assertThat(new WebAuthSettings(true, false, true, SECRET, "login", "en", "").canSend()).isFalse();
        assertThat(new WebAuthSettings(true, true, true, SECRET, "", "en", "").canSend()).isFalse();
    }
    @Test void disabledTransportCannotReachClientOrDatabase() {
        var transport = org.mockito.Mockito.mock(com.tranche.bakery.whatsapp.WhatsAppClient.class);
        var database = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var settings = new WebAuthSettings(true, false, true, SECRET, "login", "en", "");
        var service = new OtpService(database, org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                new AuthCrypto(settings), settings, transport,
                org.mockito.Mockito.mock(com.tranche.bakery.customer.CustomerRepository.class), java.time.Clock.systemUTC());
        assertThatThrownBy(() -> service.challenge("919800000021", "irrelevant", "192.0.2.1"))
                .isInstanceOf(OtpService.AuthFailure.class);
        org.mockito.Mockito.verifyNoInteractions(database, transport);
    }
}