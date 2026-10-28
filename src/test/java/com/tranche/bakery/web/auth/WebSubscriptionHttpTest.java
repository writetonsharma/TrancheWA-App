package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.subscription.SubscriptionService;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Website subscription sign-up over real PostgreSQL and security filters; providers are mocked. */
@SpringBootTest(properties = {
        "bakery.web.enabled=true", "bakery.web.checkout.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.cookie.secure=false", "bakery.cleanup.enabled=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bakery.web.allowed-origins=https://tranchebakery.in", "bakery.order.daily-capacity=15",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout='20s'"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebSubscriptionHttpTest {
    private static final String ADDRESS = "House 7, Subscription Street, DLF Phase 5";
    private static final String URL = "https://rzp.io/i/websub";
    // 4 paid weeks x 240 + 5 deliveries x the test profile's 50 delivery charge.
    private static final BigDecimal UPFRONT = new BigDecimal("1210.00");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @Autowired SubscriptionService subscriptions;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean CustomerNotifier notifier;
    @MockitoBean RazorpayService razorpay;
    private final List<Customer> created = new ArrayList<>();

    record Browser(long customerId, String phone, Cookie[] cookies, String csrf) { }

    @BeforeEach void gateway() throws Exception {
        when(razorpay.isGatewayMode()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.webhookConfigured()).thenReturn(true);
        when(razorpay.createPaymentLink(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new RazorpayService.PaymentLink("plink_WebSub1", URL));
    }

    @AfterEach void cleanup() {
        for (Customer c : created) {
            jdbc.update("DELETE FROM alerts WHERE customer_phone=? OR order_id IN (SELECT id FROM orders WHERE customer_id=?)",
                    c.getPhone(), c.getId());
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", c.getId());
            jdbc.update("DELETE FROM orders WHERE customer_id=?", c.getId());
            jdbc.update("DELETE FROM subscriptions WHERE customer_id=?", c.getId());
            jdbc.update("DELETE FROM web_customer_sessions WHERE customer_id=?", c.getId());
            jdbc.update("DELETE FROM customers WHERE id=?", c.getId());
        }
    }

    private Browser browser(boolean friendsAndFamily, BigDecimal credit) {
        var customer = new Customer();
        customer.setPhone("9196" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
        customer.setMarketingOptIn(false);
        customer.setSubscriptionEligible(friendsAndFamily);
        customer.setCreditBalance(credit);
        customer = customers.saveAndFlush(customer);
        created.add(customer);
        String binding = crypto.token(), token = crypto.token(), csrf = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO web_customer_sessions(token_hash,customer_id,browser_hash,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?,?)",
                crypto.hash("session", token), customer.getId(), crypto.hash("browser", binding), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return new Browser(customer.getId(), customer.getPhone(), new Cookie[] {new Cookie(AuthCookies.BROWSER, binding),
                new Cookie(AuthCookies.SESSION, token), new Cookie("TRANCHE_WEB_CSRF", csrf)}, csrf);
    }

    private Browser browser() { return browser(false, BigDecimal.ZERO); }

    private JsonNode call(MockHttpServletRequestBuilder request, Browser browser, boolean csrf, int status) throws Exception {
        if (browser != null) {
            request.cookie(browser.cookies());
            if (csrf) request.header("X-XSRF-TOKEN", browser.csrf());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }

    private JsonNode create(ObjectNode body, Browser browser, int status) throws Exception {
        return call(post("/api/subscriptions").contentType("application/json").content(body.toString()), browser, true, status);
    }

    private static boolean absent(JsonNode node) { return node.isNull() || node.isMissingNode(); }

    private void problem(JsonNode body, String code) {
        assertThat(body.path("valid").asBoolean(true)).isFalse();
        assertThat(body.path("problems").get(0).path("code").asText()).isEqualTo(code);
        assertThat(body.toString()).doesNotContain("SQLException", "java.lang", "plink_");
    }

    // The soonest offered day, so week 1 always falls inside the order-generation window.
    private String soonestDay() throws Exception {
        JsonNode days = call(get("/api/subscriptions/plans"), null, false, 200).path("days");
        JsonNode soonest = days.get(0);
        for (JsonNode day : days) if (day.path("firstDelivery").asText().compareTo(soonest.path("firstDelivery").asText()) < 0) soonest = day;
        return soonest.path("day").asText();
    }

    private ObjectNode body(String day) {
        ObjectNode body = mapper.createObjectNode().put("planCode", "PUB_NORMAL").put("option", 0);
        body.putArray("items").add("Classic Table White").add("Multi-Seed Rolls");
        return body.put("deliveryDay", day).put("name", "  Web Subscriber  ").put("address", "  " + ADDRESS + "  ")
                .put("deliveryPreference", "DOOR").put("loafPreference", "SLICED");
    }

    @Test void anonymousVisitorsSeePublicPlansOnlyFriendsAndFamilySeeTheirs() throws Exception {
        JsonNode plans = call(get("/api/subscriptions/plans"), null, false, 200);
        assertThat(plans.path("audience").asText()).isEqualTo("PUBLIC");
        List<String> codes = new ArrayList<>();
        plans.path("plans").forEach(p -> codes.add(p.path("code").asText()));
        assertThat(codes).contains("PUB_NORMAL").noneMatch(code -> code.startsWith("FF_"));
        JsonNode normal = plans.path("plans").get(codes.indexOf("PUB_NORMAL"));
        assertThat(normal.path("upfront").decimalValue()).isEqualByComparingTo(UPFRONT);
        assertThat(normal.path("paidWeeks").asInt()).isEqualTo(4);
        assertThat(normal.path("freeWeeks").asInt()).isEqualTo(1);
        assertThat(normal.path("options").get(0).path("components").get(0).path("choices").toString()).contains("Classic Table White");
        List<String> days = new ArrayList<>();
        plans.path("days").forEach(d -> days.add(d.path("day").asText()));
        assertThat(days).hasSize(6).doesNotContain("WEDNESDAY");
        plans.path("days").forEach(d -> assertThat(LocalDate.parse(d.path("firstDelivery").asText())).isAfter(LocalDate.now()));

        JsonNode ff = call(get("/api/subscriptions/plans"), browser(true, BigDecimal.ZERO), false, 200);
        assertThat(ff.path("audience").asText()).isEqualTo("FF");
        ff.path("plans").forEach(p -> assertThat(p.path("code").asText()).startsWith("FF_"));
    }

    @Test void signUpCreatesPendingWebSubscriptionWithPaymentLinkAndSnapshot() throws Exception {
        Browser owner = browser();
        String day = soonestDay();
        JsonNode sub = create(body(day), owner, 200).path("subscription");
        assertThat(sub.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(sub.path("channel").asText()).isEqualTo("WEB");
        assertThat(sub.path("amount").decimalValue()).isEqualByComparingTo(UPFRONT);
        assertThat(sub.path("paymentUrl").asText()).isEqualTo(URL);
        assertThat(sub.path("deliveryDay").asText()).isEqualTo(day);
        assertThat(sub.path("deliveries")).hasSize(5);
        assertThat(sub.path("fulfillment").path("address").asText()).isEqualTo(ADDRESS);
        assertThat(sub.path("fulfillment").path("name").asText()).isEqualTo("Web Subscriber");
        assertThat(sub.has("id")).isFalse();
        Long id = jdbc.queryForObject("SELECT id FROM subscriptions WHERE customer_id=?", Long.class, owner.customerId());
        verify(razorpay).createPaymentLink(argThat(amount -> amount.compareTo(UPFRONT) == 0), anyString(), eq("SUB-" + id), eq("Web Subscriber"),
                eq(owner.phone()), eq(Map.of("kind", "SUBSCRIPTION", "subscriptionId", String.valueOf(id))), any());
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id=?", String.class, owner.customerId())).isEqualTo("Web Subscriber");
        assertThat(call(get("/api/subscriptions/current"), owner, false, 200).path("subscription").path("paymentUrl").asText()).isEqualTo(URL);

        problem(create(body(day), owner, 409), "SUBSCRIPTION_EXISTS");
    }

    @Test void webhookActivationDeliversToTheSnapshotAddress() throws Exception {
        Browser owner = browser();
        JsonNode plans = call(get("/api/subscriptions/plans"), owner, false, 200);
        JsonNode pending = create(body(soonestDay()), owner, 200);
        Long id = jdbc.queryForObject("SELECT id FROM subscriptions WHERE customer_id=?", Long.class, owner.customerId());
        subscriptions.activateFromGateway(id, "pay_WebSub1", "plink_WebSub1", UPFRONT);

        JsonNode current = call(get("/api/subscriptions/current"), owner, false, 200);
        // Synthetic wire evidence for the website's validators (storefront/tests/subscribe-wire.test.mjs).
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "subscription-http-contract.json"),
                mapper.writeValueAsString(mapper.createObjectNode().<ObjectNode>set("plans", plans)
                        .<ObjectNode>set("pending", pending).set("active", current)));
        JsonNode sub = current.path("subscription");
        assertThat(sub.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(absent(sub.path("paymentUrl"))).isTrue();
        assertThat(sub.path("startDate").asText()).isEqualTo(sub.path("deliveries").get(0).path("date").asText());
        assertThat(sub.path("deliveries").get(0).path("status").asText()).isEqualTo("CONFIRMED");
        var order = jdbc.queryForMap("SELECT delivery_address,delivery_preference,loaf_preference,total_amount FROM orders WHERE subscription_id=?", id);
        assertThat(order.get("delivery_address")).isEqualTo(ADDRESS);
        assertThat(order.get("delivery_preference")).isEqualTo("DOOR");
        assertThat(order.get("loaf_preference")).isEqualTo("SLICED");
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("0");

        problem(call(post("/api/subscriptions/current/cancel"), owner, true, 409), "SUBSCRIPTION_ACTIVE");
    }

    @Test void customerCancelsUnpaidRequestOnlyAfterTheLinkIsRetired() throws Exception {
        Browser owner = browser();
        create(body(soonestDay()), owner, 200);
        doThrow(new RuntimeException("paid")).when(razorpay).cancelPaymentLink("plink_WebSub1");
        when(razorpay.fetchPaymentLink("plink_WebSub1")).thenReturn(new RazorpayService.LinkState("paid", UPFRONT, "pay_1"));
        problem(call(post("/api/subscriptions/current/cancel"), owner, true, 409), "CANCEL_UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE customer_id=?", String.class, owner.customerId()))
                .isEqualTo("PENDING_PAYMENT");

        when(razorpay.fetchPaymentLink("plink_WebSub1")).thenReturn(new RazorpayService.LinkState("expired", BigDecimal.ZERO, null));
        assertThat(absent(call(post("/api/subscriptions/current/cancel"), owner, true, 200).path("subscription"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE customer_id=?", String.class, owner.customerId()))
                .isEqualTo("CANCELLED");
        problem(call(post("/api/subscriptions/current/cancel"), owner, true, 404), "SUBSCRIPTION_NOT_FOUND");
    }

    @Test void linkFailureLeavesACancellablePendingRequestWithoutUrl() throws Exception {
        when(razorpay.createPaymentLink(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        Browser owner = browser();
        JsonNode sub = create(body(soonestDay()), owner, 200).path("subscription");
        assertThat(sub.path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(absent(sub.path("paymentUrl"))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE type='PAYMENT_LINK_FAILED' AND customer_phone=?",
                Integer.class, owner.phone())).isEqualTo(1);
        assertThat(absent(call(post("/api/subscriptions/current/cancel"), owner, true, 200).path("subscription"))).isTrue();
        verify(razorpay, never()).cancelPaymentLink(any());
    }

    @Test void strictValidationAudienceCreditAndAuthentication() throws Exception {
        Browser owner = browser();
        String day = soonestDay();
        problem(create(body(day).put("planCode", "FF_NORMAL"), owner, 409), "PLAN_UNAVAILABLE");
        ObjectNode wrongItem = body(day); wrongItem.putArray("items").add("Olive Loaf").add("Multi-Seed Rolls");
        problem(create(wrongItem, owner, 400), "INVALID_REQUEST");
        ObjectNode tooFew = body(day); tooFew.putArray("items").add("Classic Table White");
        problem(create(tooFew, owner, 400), "INVALID_REQUEST");
        for (ObjectNode bad : List.of(body("WEDNESDAY"), body(day).put("option", 99), body(day).put("extra", 1),
                body(day).put("name", "Line\u2028break"), body(day).put("address", "short"),
                body(day).put("deliveryPreference", "door"), body(day).put("loafPreference", "HALF"))) {
            problem(create(bad, owner, 400), "INVALID_REQUEST");
        }
        problem(create(body(day), browser(false, new BigDecimal("50")), 409), "CREDIT_SUPPORT_REQUIRED");
        problem(create(body(day).put("planCode", "PUB_NORMAL"), browser(true, BigDecimal.ZERO), 409), "PLAN_UNAVAILABLE");

        call(post("/api/subscriptions").contentType("application/json").content(body(day).toString()), owner, false, 403);
        problem(call(get("/api/subscriptions/current"), null, false, 401), "INVALID_SESSION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        verify(razorpay, never()).createPaymentLink(any(), anyString(), anyString(), any(), any(), any(), any());
    }
}
