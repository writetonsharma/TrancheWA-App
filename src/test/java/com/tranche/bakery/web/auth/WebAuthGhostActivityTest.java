package com.tranche.bakery.web.auth;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "bakery.web.enabled=true", "bakery.web.otp.enabled=true", "bakery.web.otp.template=web_login",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@ActiveProfiles("test")
class WebAuthGhostActivityTest {
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final ZoneId CUSTOMER_ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDateTime OLD = local(NOW.minusSeconds(30 * 86400L));
    private static final String PHONE = "919800009047";

    @Autowired OtpService otp;
    @Autowired CustomerSessionService sessions;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean(name = "webAuthClock") Clock clock;
    private final AtomicReference<String> delivered = new AtomicReference<>();
    private String browser;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM web_customer_sessions");
        jdbc.update("DELETE FROM web_otp_challenges");
        jdbc.update("DELETE FROM web_auth_limits");
        customers.findByPhone(PHONE).ifPresent(customers::delete);
        when(clock.instant()).thenReturn(NOW);
        browser = crypto.token();
        when(whatsapp.sendAuthenticationCode(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    delivered.set(invocation.getArgument(3));
                    return SendOutcome.SENT;
                });
    }

    @Test
    void newWebOnlyCustomerGetsDedicatedActivityWithoutInboundAndDefaultMarketingOptIn() {
        Customer created = login().customer();
        assertThat(created.getLastWebActivityAt()).isEqualTo(local(NOW));
        assertThat(created.getLastInboundAt()).isNull();
        assertThat(created.isMarketingOptIn()).isTrue();
    }

    @Test
    void successfulOtpProtectsExistingWebOnlyGhostWithoutInboundOrConsentSideEffects() {
        Customer customer = ghost(null, false);
        assertThat(stale()).contains(PHONE);

        var verified = login();

        assertThat(verified.customer().getId()).isEqualTo(customer.getId());
        assertThat(verified.customer().getLastWebActivityAt()).isEqualTo(local(NOW));
        assertThat(stale()).doesNotContain(PHONE);
        Customer persisted = persisted();
        assertThat(persisted.getLastInboundAt()).isNull();
        assertThat(persisted.isMarketingOptIn()).isFalse();
    }

    @Test
    void authenticatedSessionRefreshesDedicatedActivityWithoutInboundOrConsentSideEffects() {
        ghost(OLD, true);
        var verified = login();
        jdbc.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", OLD, PHONE);
        assertThat(stale()).contains(PHONE);
        Instant later = NOW.plusSeconds(60);
        when(clock.instant()).thenReturn(later);

        Customer current = sessions.currentCustomer(request(verified.token()));

        assertThat(current).isNotNull();
        assertThat(persisted().getLastWebActivityAt()).isEqualTo(local(later));
        assertThat(persisted().getLastInboundAt()).isEqualTo(OLD);
        assertThat(persisted().isMarketingOptIn()).isTrue();
        assertThat(stale()).doesNotContain(PHONE);
    }

    @Test
    void failedOtpAndExpiredOrWrongBrowserSessionsDoNotRefreshActivity() {
        ghost(null, false);
        var challenge = otp.challenge(PHONE, browser, "192.0.2.47");
        assertThatThrownBy(() -> otp.verify(challenge.challengeId(), "invalid", browser, null, "192.0.2.47"))
                .isInstanceOf(OtpService.AuthFailure.class);
        assertThat(persisted().getLastWebActivityAt()).isNull();
        var verified = otp.verify(challenge.challengeId(), delivered.get(), browser, null, "192.0.2.47");
        jdbc.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", OLD, PHONE);
        String originalBrowser = browser;
        browser = crypto.token();
        assertThat(sessions.currentCustomer(request(verified.token()))).isNull();
        browser = originalBrowser;
        jdbc.update("UPDATE web_customer_sessions SET expires_at = ?", Timestamp.from(NOW));
        assertThat(sessions.currentCustomer(request(verified.token()))).isNull();
        assertThat(persisted().getLastWebActivityAt()).isEqualTo(OLD);
        assertThat(persisted().getLastInboundAt()).isNull();
        assertThat(persisted().isMarketingOptIn()).isFalse();
    }

    @Test
    void webActivityNeverMovesBackwardsWhenRequestsObserveDifferentClockInstants() {
        ghost(null, false);
        var verified = login();
        Instant later = NOW.plusSeconds(120);
        when(clock.instant()).thenReturn(later);
        assertThat(sessions.currentCustomer(request(verified.token()))).isNotNull();
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        assertThat(sessions.currentCustomer(request(verified.token()))).isNotNull();
        assertThat(persisted().getLastWebActivityAt()).isEqualTo(local(later));
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM web_customer_sessions", Timestamp.class))
                .isEqualTo(Timestamp.from(later));
        // An OTP verification that started earlier must not overwrite newer authenticated activity.
        login();
        assertThat(persisted().getLastWebActivityAt()).isEqualTo(local(later));
    }

    @Test
    void exactWebActivityCutoffIsPreservedButOlderActivityIsEligible() {
        ghost(null, false);
        LocalDateTime cutoff = local(NOW.minusSeconds(7 * 86400L));
        jdbc.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", cutoff, PHONE);
        assertThat(stale()).doesNotContain(PHONE);
        jdbc.update("UPDATE customers SET last_web_activity_at = ? WHERE phone = ?", cutoff.minusSeconds(1), PHONE);
        assertThat(stale()).contains(PHONE);
    }

    @Test
    void ordinaryEntitySaveCannotOverwriteActivityRecordedAfterEntityWasLoaded() {
        Customer beforeLogin = ghost(null, false);
        login();
        beforeLogin.setDeliveryArea("Test area");
        customers.saveAndFlush(beforeLogin);
        assertThat(persisted().getLastWebActivityAt()).isEqualTo(local(NOW));
    }

    private Customer ghost(LocalDateTime inbound, boolean marketing) {
        Customer customer = new Customer();
        customer.setPhone(PHONE);
        customer.setCreatedAt(OLD);
        customer.setLastInboundAt(inbound);
        customer.setMarketingOptIn(marketing);
        return customers.saveAndFlush(customer);
    }

    private OtpService.Verified login() {
        var challenge = otp.challenge(PHONE, browser, "192.0.2.47");
        return otp.verify(challenge.challengeId(), delivered.get(), browser, null, "192.0.2.47");
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.BROWSER, browser), new Cookie(AuthCookies.SESSION, token));
        return request;
    }

    private Customer persisted() { return customers.findByPhone(PHONE).orElseThrow(); }

    private List<String> stale() {
        // The cleanup selector deliberately requires a transaction to hold its customer locks.
        return transactions.execute(status -> customers.findStaleGhosts(local(NOW.minusSeconds(7 * 86400L)))
                .stream().map(customer -> customer.getPhone()).toList());
    }

    private static LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, CUSTOMER_ZONE); }
}