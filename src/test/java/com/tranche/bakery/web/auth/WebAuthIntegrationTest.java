package com.tranche.bakery.web.auth;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "bakery.web.enabled=true", "bakery.web.otp.enabled=true", "bakery.web.otp.template=web_login",
    "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "bakery.web.allowed-origins=https://tranchebakery.in"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebAuthIntegrationTest {
    @Autowired OtpService otp;
    @Autowired CustomerSessionService sessions;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired WebAuthSettings settings;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean(name = "webAuthClock") Clock clock;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.tranche.bakery.web.shop.StorefrontCatalogService catalog;
    final AtomicReference<String> delivered = new AtomicReference<>();
    static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    String browser;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM web_customer_sessions");
        jdbc.update("DELETE FROM web_otp_challenges");
        jdbc.update("DELETE FROM web_auth_limits");
        when(clock.instant()).thenReturn(NOW);
        browser = crypto.token();
        delivered.set(null);
        when(whatsapp.sendAuthenticationCode(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            delivered.set(invocation.getArgument(3));
            return SendOutcome.SENT;
        });
    }
    OtpService.Challenge challenge() { return otp.challenge("+919800000021", browser, "192.0.2.1"); }
    OtpService.Verified verify(OtpService.Challenge challenge) {
        return otp.verify(challenge.challengeId(), delivered.get(), browser, null, "192.0.2.1");
    }
    MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.BROWSER, browser), new Cookie(AuthCookies.SESSION, token));
        return request;
    }
    // Exercise the actual cookie repository. Spring's csrf() processor replaces its repository
    // inside the cached filter chain and can contaminate subsequent real-bootstrap tests.
    @org.springframework.lang.NonNull org.springframework.test.web.servlet.request.RequestPostProcessor apiCsrf() {
        return request -> {
            String token = java.util.UUID.randomUUID().toString();
            request.addHeader("X-XSRF-TOKEN", requireNonNull(token));
            java.util.List<Cookie> cookies = new java.util.ArrayList<>();
            if (request.getCookies() != null) cookies.addAll(java.util.Arrays.asList(request.getCookies()));
            cookies.add(new Cookie("TRANCHE_WEB_CSRF", token));
            request.setCookies(cookies.toArray(Cookie[]::new));
            return request;
        };
    }
    @Test void otpStoredAsHmacBoundToBrowserConsumedOnceAndPrivateCustomerProjection() throws Exception {
        var challenge = challenge();
        String code = delivered.get();
        assertThat(mapper.writeValueAsString(challenge)).doesNotContain(code, "919800000021");
        assertThat(jdbc.queryForObject("SELECT code_hash FROM web_otp_challenges WHERE id = ?", String.class, challenge.challengeId()))
                .hasSize(64).isNotEqualTo(code);
        assertThatThrownBy(() -> otp.verify(challenge.challengeId(), code, crypto.token(), null, "192.0.2.1"))
                .isInstanceOf(OtpService.AuthFailure.class);
        var verified = verify(challenge);
        assertThat(sessions.currentCustomer(request(verified.token())).getId()).isEqualTo(verified.customer().getId());
        assertThatThrownBy(() -> verify(challenge)).isInstanceOf(OtpService.AuthFailure.class);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM web_customer_sessions", String.class)).isNotEqualTo(verified.token());
        mvc.perform(get("/api/session").cookie(new Cookie(AuthCookies.BROWSER, browser), new Cookie(AuthCookies.SESSION, verified.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.customer.id").value(verified.customer().getId()))
                .andExpect(jsonPath("$.customer.phone").doesNotExist()).andExpect(jsonPath("$.customer.creditBalance").doesNotExist());
    }
    @Test void expiresAtFiveMinutesAndFailedAttemptsStayCommitted() {
        var exhausted = challenge();
        String code = delivered.get();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> otp.verify(exhausted.challengeId(), "invalid", browser, null, "192.0.2.1"))
                    .isInstanceOf(OtpService.AuthFailure.class);
        }
        assertThat(jdbc.queryForObject("SELECT attempts FROM web_otp_challenges WHERE id = ?", Integer.class, exhausted.challengeId())).isEqualTo(5);
        assertThatThrownBy(() -> otp.verify(exhausted.challengeId(), code, browser, null, "192.0.2.1")).isInstanceOf(OtpService.AuthFailure.class);
        var expired = challenge();
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertThatThrownBy(() -> verify(expired)).isInstanceOf(OtpService.AuthFailure.class);
    }
    @Test void resendInvalidatesOldChallengeAndSessionRotationLogoutAndIdleExpiryWork() {
        var old = challenge();
        String oldCode = delivered.get();
        var fresh = challenge();
        assertThatThrownBy(() -> otp.verify(old.challengeId(), oldCode, browser, null, "192.0.2.1")).isInstanceOf(OtpService.AuthFailure.class);
        var first = verify(fresh);
        var second = verify(challenge());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(sessions.currentCustomer(request(first.token()))).isNull();
        assertThat(sessions.currentCustomer(request(second.token()))).isNotNull();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1800));
        assertThat(sessions.currentCustomer(request(second.token()))).isNull();
        when(clock.instant()).thenReturn(NOW);
        otp.invalidateBrowser(browser);
        assertThat(sessions.currentCustomer(request(second.token()))).isNull();
    }
    @Test void absoluteExpiryCannotBeExtendedByActivityAndBindingCannotBeSwapped() {
        var verified = verify(challenge());
        jdbc.update("UPDATE web_customer_sessions SET expires_at = ?, last_seen_at = ?", Timestamp.from(NOW), Timestamp.from(NOW));
        assertThat(sessions.currentCustomer(request(verified.token()))).isNull();
        jdbc.update("UPDATE web_customer_sessions SET expires_at = ?", Timestamp.from(NOW.plusSeconds(1000)));
        String originalBrowser = browser;
        browser = crypto.token();
        assertThat(sessions.currentCustomer(request(verified.token()))).isNull();
        browser = originalBrowser;
        assertThat(sessions.currentCustomer(request(verified.token()))).isNotNull();
    }
    @Test void newCustomersOptInByDefaultWhileExistingConsentAndInboundWindowAreUntouched() {
        String phone = "919800000022";
        Customer customer = customers.findByPhone(phone).orElseGet(() -> {
            Customer created = new Customer(); created.setPhone(phone); return customers.save(created);
        });
        customer.setLastInboundAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        customer.setMarketingOptIn(false);
        customers.saveAndFlush(customer);
        var challenge = otp.challenge(phone, browser, "192.0.2.1");
        var loggedIn = verify(challenge);
        assertThat(loggedIn.customer().getId()).isEqualTo(customer.getId());
        var persisted = customers.findById(requireNonNull(customer.getId())).orElseThrow();
        assertThat(persisted.getLastInboundAt()).isEqualTo(customer.getLastInboundAt());
        assertThat(persisted.isMarketingOptIn()).isFalse();
        // Unique test phone; no inbound helper, same marketing default as WhatsApp customers.
        var newChallenge = otp.challenge("919800000023", browser, "192.0.2.1");
        var created = verify(newChallenge).customer();
        assertThat(created.isMarketingOptIn()).isTrue();
        assertThat(created.getLastInboundAt()).isNull();
    }
    @Test void indianShorthandReusesExistingFriendsFamilyCustomerPricingAndSessionOwnership() throws Exception {
        String phone = "919800000061";
        Customer existing = customers.findByPhone(phone).orElseGet(() -> {
            Customer created = new Customer(); created.setPhone(phone); return created;
        });
        existing.setName("Website F&F fixture");
        existing.setPricingOverride(new java.math.BigDecimal("150"));
        existing.getCategoryPrices().put("Sweet Bakes", new java.math.BigDecimal("125"));
        existing.getItemPrices().put("Cinnamon Rolls", new java.math.BigDecimal("100"));
        existing.setSubscriptionEligible(true);
        existing.setCreditBalance(new java.math.BigDecimal("70"));
        existing = customers.saveAndFlush(existing);
        long countBefore = customers.count();
        Long customerId = existing.getId();
        for (String input : new String[]{"9800000061", phone, "+" + phone}) {
            var challenge = otp.challenge(input, browser, "192.0.2.61");
            assertThat(jdbc.queryForObject("SELECT phone FROM web_otp_challenges WHERE id = ?", String.class,
                    challenge.challengeId())).isEqualTo(phone);
            var verified = verify(challenge);
            Customer owner = sessions.currentCustomer(request(verified.token()));
            assertThat(owner.getId()).isEqualTo(customerId);
            assertThat(verified.customer().getId()).isEqualTo(customerId);
            assertThat(owner.getPhone()).isEqualTo(phone);
            assertThat(owner.getName()).isEqualTo("Website F&F fixture");
            assertThat(owner.isFriendsAndFamily()).isTrue();
            assertThat(owner.isSubscriptionEligible()).isTrue();
            assertThat(owner.unitPriceFor("Cinnamon Rolls", "Sweet Bakes")).isEqualByComparingTo("100");
            assertThat(owner.unitPriceFor("Other", "Sweet Bakes")).isEqualByComparingTo("125");
            assertThat(owner.unitPriceFor("Other", "Other")).isEqualByComparingTo("150");
            assertThat(owner.getCreditBalance()).isEqualByComparingTo("70");
            assertThat(jdbc.queryForObject("SELECT customer_id FROM web_customer_sessions WHERE token_hash = ?",
                    Long.class, crypto.hash("session", verified.token()))).isEqualTo(customerId);
            mvc.perform(get("/api/session").cookie(new Cookie(AuthCookies.BROWSER, browser),
                    new Cookie(AuthCookies.SESSION, verified.token())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.customer.id").value(customerId));
        }
        assertThat(customers.count()).isEqualTo(countBefore);
        assertThat(customers.findByPhone("9800000061")).isEmpty();
        org.mockito.Mockito.verify(whatsapp, times(3)).sendAuthenticationCode(eq(phone), anyString(), anyString(), anyString());
    }
    @Test void indianSpellingsSharePhoneBudgetAcrossIndependentBrowsersAndIps() {
        String phone = "919800000062";
        String[] inputs = {"9800000062", phone, "+" + phone, "9800000062", phone};
        for (int i = 0; i < inputs.length; i++)
            otp.challenge(inputs[i], crypto.token(), "192.0.2." + (70 + i));
        String bucket = "phone:" + crypto.hash("phone", phone);
        assertThat(jdbc.queryForObject("SELECT hits FROM web_auth_limits WHERE bucket = ?", Integer.class, bucket)).isEqualTo(5);
        for (String input : new String[]{"9800000062", phone, "+" + phone})
            assertThatThrownBy(() -> otp.challenge(input, crypto.token(), "192.0.2.80"))
                    .isInstanceOfSatisfying(OtpService.AuthFailure.class, failure -> assertThat(failure.status).isEqualTo(429));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_auth_limits WHERE bucket LIKE 'phone:%'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_otp_challenges", Integer.class)).isEqualTo(5);
        org.mockito.Mockito.verify(whatsapp, times(5)).sendAuthenticationCode(eq(phone), anyString(), anyString(), anyString());
    }
    @Test void explicitTenDigitInternationalIdentityIsNotReinterpretedAsIndian() {
        for (String input : new String[]{"+1980000063", "+14155550063", "441234560063", "12345678", "123456789012345"}) {
            String expected = input.startsWith("+") ? input.substring(1) : input;
            var challenge = otp.challenge(input, browser, "192.0.2.63");
            assertThat(jdbc.queryForObject("SELECT phone FROM web_otp_challenges WHERE id = ?", String.class,
                    challenge.challengeId())).isEqualTo(expected);
            assertThat(verify(challenge).customer().getPhone()).isEqualTo(expected);
            org.mockito.Mockito.verify(whatsapp).sendAuthenticationCode(eq(expected), anyString(), anyString(), anyString());
        }
        assertThat(customers.findByPhone("911980000063")).isEmpty();
    }
    @Test void invalidPhoneFormatsNeverConsumeBudgetsOrSendCodes() {
        for (String input : new String[]{"0980000064", "98000 00064", "9800000064 ", "+0980000064", "９８００００００６４"})
            assertThatThrownBy(() -> otp.challenge(input, browser, "192.0.2.64"))
                    .isInstanceOfSatisfying(OtpService.AuthFailure.class, failure -> assertThat(failure.status).isEqualTo(400));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_auth_limits", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_otp_challenges", Integer.class)).isZero();
        verifyNoInteractions(whatsapp);
    }
    @Test void durablePhoneBrowserAndIpBudgetsAndSenderFailuresAreClosed() {
        for (int i = 0; i < 5; i++) challenge();
        assertThatThrownBy(this::challenge).isInstanceOf(OtpService.AuthFailure.class);
        // Counters are persisted, not held in the service instance.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_auth_limits WHERE hits >= 5", Integer.class)).isGreaterThan(0);
        OtpService restarted = new OtpService(jdbc, transactions, crypto, settings, whatsapp, customers, clock);
        assertThatThrownBy(() -> restarted.challenge("919800000025", browser, "192.0.2.3"))
            .isInstanceOf(OtpService.AuthFailure.class);
        assertThatThrownBy(() -> otp.challenge("919800000021", crypto.token(), "192.0.2.2"))
                .isInstanceOf(OtpService.AuthFailure.class);
        when(whatsapp.sendAuthenticationCode(anyString(), anyString(), anyString(), anyString())).thenReturn(SendOutcome.FAILED);
        assertThatThrownBy(() -> otp.challenge("919800000024", crypto.token(), "192.0.2.2"))
                .isInstanceOf(OtpService.AuthFailure.class);
        assertThat(jdbc.queryForObject("SELECT state FROM web_otp_challenges WHERE phone = '919800000024'", String.class)).isEqualTo("INVALID");
    }
    @Test void perIpBudgetSurvivesNewBrowsersAndDifferentPhones() {
        for (int i = 0; i < 20; i++) otp.challenge("9198000010" + String.format("%02d", i), crypto.token(), "192.0.2.40");
        assertThatThrownBy(() -> otp.challenge("919800001099", crypto.token(), "192.0.2.40"))
                .isInstanceOf(OtpService.AuthFailure.class);
    }
    @Test void concurrentVerificationOnlyOneWins() throws Exception {
        var challenge = challenge();
        String code = delivered.get();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                try { otp.verify(challenge.challengeId(), code, browser, null, "192.0.2.1"); return true; }
                catch (OtpService.AuthFailure ex) { return false; }
            };
            var results = executor.invokeAll(java.util.List.of(attempt, attempt), 10, java.util.concurrent.TimeUnit.SECONDS);
            int successes = 0;
            for (var result : results) if (result.get()) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_sessions", Integer.class)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    @Test void anonymousCsrfBootstrapWorksAndAllUnsafeApiMethodsRequireCsrfAndAdminIsNotCustomer() throws Exception {
        var bootstrap = mvc.perform(get("/api/session").with(requireNonNull(user("admin").roles("ADMIN"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.customer").isEmpty())
                .andExpect(jsonPath("$.csrfHeaderName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty()).andReturn();
        String token = mapper.readTree(bootstrap.getResponse().getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/auth/challenge").contentType("application/json").content("{\"phone\":\"919800000021\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/challenge").cookie(new Cookie("TRANCHE_WEB_CSRF", token), new Cookie(AuthCookies.BROWSER, browser))
                .header("X-XSRF-TOKEN", token).contentType("application/json").content("{\"phone\":\"919800000021\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challengeId").isNotEmpty()).andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/verify").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quote").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void credentialedCorsIsExactAndHostOnlyCookiesAreSecure() throws Exception {
        mvc.perform(get("/api/session").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Location"));
        var response = mvc.perform(get("/api/session").header("Origin", "https://tranchebakery.in"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://tranchebakery.in"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true")).andReturn().getResponse();
        assertThat(response.getHeaders("Set-Cookie")).allSatisfy(cookie ->
            assertThat(cookie).contains("Secure", "HttpOnly").doesNotContain("Domain="));
        // Servlet 6 stores SameSite as a generic Cookie attribute; MockHttpServletResponse's
        // Set-Cookie string omits it, unlike the real Tomcat serializer. Check the actual cookie.
        assertThat(requireNonNull(response.getCookie("TRANCHE_WEB_CSRF")).getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(requireNonNull(response.getCookie(AuthCookies.BROWSER)).getAttribute("SameSite")).isEqualTo("Lax");
        mvc.perform(get("/api/session").header("Origin", "http://localhost"))
                .andExpect(status().isOk());
    }
    @Test void verifySetsSessionCookieAndHttpLogoutRevokesIt() throws Exception {
        var challenge = challenge();
        var response = mvc.perform(post("/api/auth/verify").with(apiCsrf()).cookie(new Cookie(AuthCookies.BROWSER, browser))
                .contentType("application/json").content(requireNonNull(mapper.writeValueAsString(new AuthController.VerifyInput(challenge.challengeId(), delivered.get())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.customer.phone").doesNotExist()).andReturn().getResponse();
        String sessionHeader = response.getHeaders("Set-Cookie").stream().filter(c -> c.startsWith(AuthCookies.SESSION + "=")).findFirst().orElseThrow();
        String token = sessionHeader.split(";", 2)[0].substring((AuthCookies.SESSION + "=").length());
        assertThat(sessionHeader).contains("Secure", "HttpOnly", "SameSite=Lax").doesNotContain("Domain=");
        mvc.perform(post("/api/auth/logout").with(apiCsrf()).cookie(new Cookie(AuthCookies.BROWSER, browser), new Cookie(AuthCookies.SESSION, token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        assertThat(sessions.currentCustomer(request(token))).isNull();
    }

        @Test void loginRenewsBrowserBindingAndRotatesCsrfOnLoginAndLogout() throws Exception {
        var bootstrap = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn().getResponse();
        String oldCsrf = mapper.readTree(bootstrap.getContentAsString()).get("csrfToken").asText();
        var challenge = challenge();
        var response = mvc.perform(post("/api/auth/verify")
            .cookie(new Cookie(AuthCookies.BROWSER, browser), new Cookie("TRANCHE_WEB_CSRF", oldCsrf))
            .header("X-XSRF-TOKEN", oldCsrf).contentType("application/json")
            .content(requireNonNull(mapper.writeValueAsString(new AuthController.VerifyInput(challenge.challengeId(), delivered.get())))))
            .andExpect(status().isOk()).andReturn().getResponse();
        String newCsrf = mapper.readTree(response.getContentAsString()).get("csrfToken").asText();
        assertThat(newCsrf).isNotEqualTo(oldCsrf);
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
            assertThat(cookie).startsWith(AuthCookies.BROWSER + "=" + browser + ";").contains("Max-Age=604800"));
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
            assertThat(cookie).startsWith("TRANCHE_WEB_CSRF=" + newCsrf + ";"));
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("TRANCHE_WEB_CSRF", newCsrf))
            .header("X-XSRF-TOKEN", oldCsrf)).andExpect(status().isForbidden());
        var logout = mvc.perform(post("/api/auth/logout").cookie(new Cookie("TRANCHE_WEB_CSRF", newCsrf),
                new Cookie(AuthCookies.BROWSER, browser))
            .header("X-XSRF-TOKEN", newCsrf)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(mapper.readTree(logout.getContentAsString()).get("csrfToken").asText()).isNotEqualTo(newCsrf);
        }

        @Test void quoteUsesOnlyVerifiedCookiesAndStopsPersonalizingAfterLogout() throws Exception {
        var verified = verify(challenge());
        Cookie binding = new Cookie(AuthCookies.BROWSER, browser);
        Cookie session = new Cookie(AuthCookies.SESSION, verified.token());
        // Empty basket still goes through the complete quote identity boundary without reserving anything.
        jdbc.update("UPDATE web_customer_sessions SET last_seen_at = ?", Timestamp.from(NOW.minusSeconds(60)));
        mvc.perform(post("/api/quote").with(apiCsrf()).cookie(binding, session)
            .contentType("application/json").content("{\"lines\":[]}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"));
        assertThat(requireNonNull(jdbc.queryForObject("SELECT last_seen_at FROM web_customer_sessions", Timestamp.class)).toInstant()).isEqualTo(NOW);
        org.mockito.Mockito.verify(catalog).quote(any(), argThat(customer -> customer != null && customer.getId().equals(verified.customer().getId())));
        clearInvocations(catalog);
        mvc.perform(post("/api/auth/logout").with(apiCsrf()).cookie(binding, session))
            .andExpect(status().isOk());
        mvc.perform(post("/api/quote").with(apiCsrf()).cookie(binding, session)
            .with(requireNonNull(user("admin").roles("ADMIN"))).header("X-Customer-Id", verified.customer().getId())
            .contentType("application/json").content("{\"lines\":[]}"))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(catalog).quote(any(), isNull());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_sessions", Integer.class)).isZero();
        }
}