package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.CapacityReservationException;
import com.tranche.bakery.order.OrderRepository;
import com.tranche.bakery.order.OrderService;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Replacement checkout contract: real loopback HTTP/security and local PostgreSQL,
 * never a live provider. Independent of WebCartHttpTest's no-provider fixture.
 * The parent coordinates the initial red run and subsequent implementation runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.forward-headers-strategy=none",
        "bakery.web.enabled=true", "bakery.web.checkout.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.cookie.secure=false", "bakery.cleanup.enabled=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bakery.web.allowed-origins=https://tranchebakery.in", "bakery.order.daily-capacity=15",
        "bakery.batch-discount.enabled=false",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout='20s'"
})
@ActiveProfiles("test")
class WebCheckoutHttpTest {
    private static final String ADDRESS = "Flat 42, Checkout Lane, Gurugram";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @Autowired AdminService admin;
    @Autowired OrderRepository orders;
    @Autowired OrderService orderService;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean CapacityLock capacity;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean CustomerNotifier notifier;
    @MockitoBean RazorpayService razorpay;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> customerIds = new ArrayList<>();
    Long categoryId;
    Long itemId;
    Browser owner;
    LocalDate date;

    record Browser(long customerId, String cookies, String csrf) {}

    @BeforeEach void setup() throws Exception {
        categoryId = jdbc.queryForObject("INSERT INTO menu_categories(name,display_order,active) VALUES (?,0,true) RETURNING id",
                Long.class, "Web-checkout-" + UUID.randomUUID());
        itemId = jdbc.queryForObject("INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,'Checkout test loaf',200,0,true) RETURNING id",
                Long.class, categoryId);
        owner = browser();
        date = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        when(razorpay.isGatewayMode()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.webhookConfigured()).thenReturn(true);
        when(razorpay.createPaymentLink(any(BigDecimal.class), anyString(), anyString(), anyString(),
                anyString(), anyMap(), any(Instant.class))).thenAnswer(invocation -> {
                    String reference = invocation.getArgument(2);
                    assertThat(reference.length()).isLessThanOrEqualTo(40);
                    String unique = UUID.randomUUID().toString().replace("-", "");
                    return new RazorpayService.PaymentLink("plink_" + unique, "https://rzp.io/i/" + unique);
                });
    }

    @AfterEach void cleanup() {
        // Attempts reference orders; payments/items must also go before orders/customers.
        // Keep cleanup usable during the initial red run before the attempt migration exists.
        boolean attemptsExist = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('web_checkout_attempts') IS NOT NULL", Boolean.class));
        for (Long id : customerIds) {
            jdbc.update("DELETE FROM alerts WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            if (attemptsExist) jdbc.update("DELETE FROM web_checkout_attempts WHERE customer_id=?", id);
            jdbc.update("DELETE FROM orders WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
        jdbc.update("DELETE FROM menu_items WHERE category_id=?", categoryId);
        jdbc.update("DELETE FROM menu_categories WHERE id=?", categoryId);
        verifyNoInteractions(whatsapp);
    }

    Browser browser() {
        Customer customer = new Customer();
        customer.setPhone("9196" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
        customer.setMarketingOptIn(false);
        customer = customers.saveAndFlush(customer);
        customerIds.add(customer.getId());
        return session(customer.getId());
    }

    Browser session(long customerId) {
        String binding = crypto.token();
        String token = crypto.token();
        String csrf = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO web_customer_sessions(token_hash,customer_id,browser_hash,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?,?)",
                crypto.hash("session", token), customerId, crypto.hash("browser", binding), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return new Browser(customerId, AuthCookies.BROWSER + "=" + binding + "; " + AuthCookies.SESSION + "=" + token
                + "; TRANCHE_WEB_CSRF=" + csrf, csrf);
    }

    HttpResponse<String> call(String method, String path, String body, Browser browser, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
        if (browser != null) {
            request.header("Cookie", browser.cookies());
            if (csrf) request.header("X-XSRF-TOKEN", browser.csrf());
        }
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        return response;
    }

    JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return mapper.readTree(response.body());
    }

    void error(HttpResponse<String> response, int status, String code) throws Exception {
        var body = json(response, status);
        assertThat(body.path("valid").asBoolean(true)).isFalse();
        assertThat(body.path("problems").get(0).path("code").asText()).isEqualTo(code);
        assertThat(body.toString()).doesNotContain(ADDRESS, "SQLException", "java.lang", "plink_");
    }

    JsonNode cart(Browser browser, long version, int quantity) throws Exception {
        return json(call("PUT", "/api/cart", "{\"version\":" + version + ",\"lines\":[{\"itemId\":" + itemId
                + ",\"quantity\":" + quantity + "}],\"date\":\"" + date + "\"}", browser, true), 200);
    }

    JsonNode prepare(Browser browser, long version) throws Exception {
        var prepared = json(call("POST", "/api/checkout/prepare", "{\"cartVersion\":" + version + "}", browser, true), 200);
        assertThat(prepared.path("cartVersion").asLong()).isEqualTo(version);
        assertThat(prepared.path("quoteToken").asText()).isNotBlank();
        assertThat(prepared.path("quote").path("valid").asBoolean()).isTrue();
        assertThat(Instant.parse(prepared.path("expiresAt").asText())).isAfter(Instant.now());
        return prepared;
    }

    ObjectNode request(JsonNode prepared, String key) {
        var body = mapper.createObjectNode();
        body.put("idempotencyKey", key);
        body.put("cartVersion", prepared.path("cartVersion").asLong());
        body.put("quoteToken", prepared.path("quoteToken").asText());
        body.put("name", "Checkout Snapshot Name");
        body.put("address", ADDRESS);
        body.put("deliveryPreference", "GATE");
        body.put("loafPreference", "WHOLE");
        return body;
    }

    String key() { return UUID.randomUUID().toString(); }

    JsonNode checkout(Browser browser, ObjectNode body) throws Exception {
        return json(call("POST", "/api/checkout", body.toString(), browser, true), 200);
    }

    JsonNode status(Browser browser, String id) throws Exception {
        return json(call("GET", "/api/checkout/" + id, null, browser, false), 200);
    }

    Map<String, Object> attempt(String id) {
        return jdbc.queryForMap("SELECT id,customer_id,order_id,state,provider_link_id,provider_url,expires_at,quote_hash,snapshot "
                + "FROM web_checkout_attempts WHERE id=?", id);
    }

    long orderId(String attemptId) { return ((Number) attempt(attemptId).get("order_id")).longValue(); }

    void noAcceptance(Browser browser) throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, browser.customerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments p JOIN orders o ON o.id=p.order_id WHERE o.customer_id=?",
            Integer.class, browser.customerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, browser.customerId())).isZero();
        verify(razorpay, never()).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    void onlyOneProviderCall() throws Exception {
        verify(razorpay, times(1)).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    void noPaymentUrl(JsonNode body) {
        assertThat(body.path("paymentUrl").isMissingNode() || body.path("paymentUrl").isNull()).isTrue();
    }

    String boundaryLines(String shape, int total) {
        if ("single".equals(shape)) return "[{\"itemId\":" + itemId + ",\"quantity\":" + total + "}]";
        if ("independent".equals(shape)) {
            var lines = mapper.createArrayNode();
            lines.addObject().put("itemId", itemId).put("quantity", 1);
            for (int i = 1; i < total; i++) {
                long id = Objects.requireNonNull(jdbc.queryForObject(
                        "INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,?,200,?,true) RETURNING id",
                        Long.class, categoryId, "Other checkout loaf " + i, i));
                lines.addObject().put("itemId", id).put("quantity", 1);
            }
            return lines.toString();
        }
        return "[{\"itemId\":" + itemId + ",\"quantity\":2},{\"itemId\":" + itemId
                + ",\"quantity\":" + (total - 2) + "}]";
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "duplicates", "independent"})
    void fiveItemsCanBeSavedPreparedAndAccepted(String shape) throws Exception {
        String lines = boundaryLines(shape, 5);
        var saved = json(call("PUT", "/api/cart", "{\"version\":0,\"lines\":" + lines
                + ",\"date\":\"" + date + "\"}", owner, true), 200);
        assertThat(saved.path("quote").path("itemCount").asInt()).isEqualTo(5);
        assertThat(saved.path("lines")).hasSize("independent".equals(shape) ? 5 : 1);
        var prepared = prepare(owner, 1);
        assertThat(prepared.path("quote")).isEqualTo(saved.path("quote"));
        var result = checkout(owner, request(prepared, key()));
        assertThat(result.path("status").asText()).isEqualTo("PAYABLE");
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM order_items WHERE order_id=?",
                Integer.class, orderId(result.path("id").asText()))).isEqualTo(5);
        onlyOneProviderCall();
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "duplicates", "independent"})
    void sixStoredItemsRejectPrepareAndNewAcceptanceWithoutPersistenceOrProviderCalls(String shape) throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        // Simulate a pre-policy basket at the same edit version. The old token must
        // not bypass the limit, and rejection must precede token-hash validation.
        jdbc.update("UPDATE web_carts SET lines=?::jsonb WHERE customer_id=?", boundaryLines(shape, 6), owner.customerId());
        var storedCart = jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId());
        var storedQuote = jdbc.queryForMap("SELECT * FROM web_checkout_quotes WHERE customer_id=?", owner.customerId());
        clearInvocations(razorpay);
        error(call("POST", "/api/checkout/prepare", "{\"cartVersion\":1}", owner, true), 409, "RETAIL_LIMIT_EXCEEDED");
        error(call("POST", "/api/checkout", body.toString(), owner, true), 409, "RETAIL_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId())).isEqualTo(storedCart);
        assertThat(jdbc.queryForMap("SELECT * FROM web_checkout_quotes WHERE customer_id=?", owner.customerId())).isEqualTo(storedQuote);
        noAcceptance(owner);
        verifyNoInteractions(razorpay, notifier);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYABLE", "CREATING", "UNKNOWN", "PAID"})
    void acceptedLegacySixItemCheckoutStillReplaysAndReportsStatusWithoutDispatch(String state) throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        String id = checkout(owner, body).path("id").asText();
        long orderId = orderId(id);
        // Seed an internally consistent historical six-unit acceptance without
        // weakening the new acceptance guard or invoking a live payment provider.
        jdbc.update("UPDATE web_carts SET lines=?::jsonb WHERE customer_id=?", boundaryLines("single", 6), owner.customerId());
        var legacyQuote = json(call("GET", "/api/cart", null, owner, false), 200).path("quote");
        assertThat(legacyQuote.path("itemCount").asInt()).isEqualTo(6);
        var snapshot = (ObjectNode) mapper.readTree(attempt(id).get("snapshot").toString());
        snapshot.set("quote", legacyQuote);
        String quoteHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(legacyQuote)));
        BigDecimal amount = legacyQuote.path("totalAmount").decimalValue();
        tx.executeWithoutResult(transaction -> {
            jdbc.update("UPDATE web_checkout_attempts SET snapshot=?::jsonb,quote_hash=? WHERE id=?", snapshot.toString(), quoteHash, id);
            jdbc.update("UPDATE order_items SET quantity=6,subtotal=1200 WHERE order_id=?", orderId);
            jdbc.update("UPDATE orders SET total_amount=? WHERE id=?", amount, orderId);
            jdbc.update("UPDATE payments SET amount=? WHERE order_id=?", amount, orderId);
            if (List.of("CREATING", "UNKNOWN").contains(state)) {
                jdbc.update("UPDATE web_checkout_attempts SET state=?,updated_at=clock_timestamp(),provider_link_id=NULL,provider_url=NULL WHERE id=?", state, id);
                jdbc.update("UPDATE payments SET gateway_link_id=NULL,gateway_link_url=NULL WHERE order_id=?", orderId);
            } else if ("PAID".equals(state)) {
                jdbc.update("UPDATE orders SET status='CONFIRMED' WHERE id=?", orderId);
                jdbc.update("UPDATE payments SET status='GATEWAY_CAPTURED',gateway_payment_id='pay_legacy_six' WHERE order_id=?", orderId);
            }
        });
        jdbc.update("UPDATE web_checkout_quotes SET expires_at=? WHERE customer_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        var storedAttempt = attempt(id);
        var storedOrder = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId);
        var storedPayment = jdbc.queryForMap("SELECT * FROM payments WHERE order_id=?", orderId);
        clearInvocations(razorpay);
        var current = status(owner, id);
        assertThat(current.path("status").asText()).isEqualTo(state);
        if ("PAYABLE".equals(state)) assertThat(current.path("paymentUrl").asText()).startsWith("https://rzp.io/i/");
        else noPaymentUrl(current);
        assertThat(checkout(session(owner.customerId()), body)).isEqualTo(current);
        assertThat(status(owner, id)).isEqualTo(current);
        assertThat(attempt(id)).isEqualTo(storedAttempt);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId)).isEqualTo(storedOrder);
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE order_id=?", orderId)).isEqualTo(storedPayment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void prepareBindsAnOpaqueQuoteWithoutReservingCapacityOrCreatingPayment() throws Exception {
        var saved = cart(owner, 0, 2);
        var prepared = prepare(owner, 1);
        assertThat(prepared.path("quote")).isEqualTo(saved.path("quote"));
        assertThat(Instant.parse(prepared.path("expiresAt").asText())).isBefore(Instant.now().plusSeconds(610));
        assertThat(prepared.path("quoteToken").asText()).doesNotContain(String.valueOf(owner.customerId()) + ":");
        noAcceptance(owner);
    }

    @Test void acceptancePersistsIndependentImmutableOrderPaymentAndFulfillmentSnapshot() throws Exception {
        jdbc.update("UPDATE customers SET name='Profile Name',delivery_address='Original profile address' WHERE id=?", owner.customerId());
        cart(owner, 0, 2);
        var prepared = prepare(owner, 1);
        var result = checkout(owner, request(prepared, key()));
        assertThat(result.path("status").asText()).isEqualTo("PAYABLE");
        assertThat(result.path("id").asText()).isNotBlank();
        assertThat(result.path("paymentUrl").asText()).startsWith("https://rzp.io/i/");
        assertThat(Instant.parse(result.path("expiresAt").asText())).isAfter(Instant.now());
        var fields = new ArrayList<String>();
        result.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "status", "paymentUrl", "expiresAt");
        String id = result.path("id").asText();
        var stored = attempt(id);
        assertThat(((Number) stored.get("customer_id")).longValue()).isEqualTo(owner.customerId());
        assertThat(stored.get("provider_link_id").toString()).startsWith("plink_");
        assertThat(stored.get("provider_url")).isEqualTo(result.path("paymentUrl").asText());
        assertThat(stored.get("expires_at")).isNotNull();
        assertThat(stored.get("quote_hash").toString()).isNotBlank();
        var snapshot = mapper.readTree(stored.get("snapshot").toString());
        assertThat(snapshot.findValue("name").asText()).isEqualTo("Checkout Snapshot Name");
        assertThat(snapshot.findValue("address").asText()).isEqualTo(ADDRESS);
        assertThat(snapshot.findValue("deliveryPreference").asText()).isEqualTo("GATE");
        assertThat(snapshot.findValue("loafPreference").asText()).isEqualTo("WHOLE");
        // jsonb may normalize 200.0 to 200: compare numeric value, retaining exact JSON
        // structure, field names, array order, strings, booleans and null/missing semantics.
        assertThat(snapshot.findValue("quote").equals((left, right) ->
            left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue())
                : left.equals(right) ? 0 : 1, prepared.path("quote"))).isTrue();
        assertThat(snapshot.toString()).contains(date.toString());
        long orderId = orderId(id);
        var order = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId);
        assertThat(order.get("channel")).isEqualTo("WEB");
        assertThat(order.get("status")).isEqualTo("PENDING_CONFIRMATION");
        assertThat(order.get("source")).isEqualTo("RETAIL");
        assertThat(order.get("fulfillment_type")).isEqualTo("DELIVERY");
        assertThat(order.get("delivery_address")).isEqualTo(ADDRESS);
        assertThat(order.get("delivery_preference")).isEqualTo("GATE");
        assertThat(order.get("loaf_preference")).isEqualTo("WHOLE");
        assertThat(order.get("delivery_date").toString()).isEqualTo(date.toString());
        var payment = jdbc.queryForMap("SELECT * FROM payments WHERE order_id=?", orderId);
        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat(payment.get("provider")).isEqualTo("RAZORPAY");
        assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo(prepared.path("quote").path("totalAmount").decimalValue());
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo((BigDecimal) payment.get("amount"));
        assertThat(payment.get("gateway_link_id")).isEqualTo(stored.get("provider_link_id"));
        assertThat(payment.get("gateway_link_url")).isEqualTo(stored.get("provider_url"));
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE order_id=?", Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId)).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("SELECT delivery_address FROM customers WHERE id=?", String.class, owner.customerId()))
                .isEqualTo("Original profile address");
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id=?", String.class, owner.customerId())).isEqualTo("Profile Name");
        cart(owner, 1, 3);
        jdbc.update("UPDATE menu_items SET price=300 WHERE id=?", itemId);
        jdbc.update("UPDATE customers SET name='Later profile',delivery_address='Later profile address' WHERE id=?", owner.customerId());
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId)).isEqualTo(order);
        assertThat(attempt(id).get("snapshot").toString()).isEqualTo(stored.get("snapshot").toString());
        assertThat(status(session(owner.customerId()), id)).isEqualTo(result);
        onlyOneProviderCall();
    }

    @Test void acceptanceFillsOnlyABlankCustomerNameFromCheckout() throws Exception {
        jdbc.update("UPDATE customers SET name='   ' WHERE id=?", owner.customerId());
        cart(owner, 0, 1);
        checkout(owner, request(prepare(owner, 1), key()));
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id=?", String.class, owner.customerId()))
                .isEqualTo("Checkout Snapshot Name");
        onlyOneProviderCall();
    }

    @Test void providerDispatchSeesCommittedCreatingAttemptAndPaymentOutsideTransaction() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var row = jdbc.queryForMap("SELECT id,order_id,state FROM web_checkout_attempts WHERE customer_id=?", owner.customerId());
            assertThat(row.get("state")).isEqualTo("CREATING");
            long orderId = ((Number) row.get("order_id")).longValue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
            assertThat((BigDecimal) invocation.getArgument(0)).isEqualByComparingTo(prepared.path("quote").path("totalAmount").decimalValue());
            String reference = invocation.getArgument(2);
            assertThat(reference.length()).isLessThanOrEqualTo(40);
            assertThat(row.get("id").toString()).hasSize(32);
            assertThat(reference).isEqualTo("web_" + row.get("id"));
            assertThat(reference).contains(row.get("id").toString());
            Map<?, ?> notes = invocation.getArgument(5);
            assertThat(notes.get("kind")).isEqualTo("ORDER");
            assertThat(notes.get("orderId")).isEqualTo(String.valueOf(orderId));
            assertThat(notes.values().stream().map(String::valueOf).toList()).contains(row.get("id").toString());
            return new RazorpayService.PaymentLink("plink_committed", "https://rzp.io/i/committed");
        }).when(razorpay).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
        assertThat(checkout(owner, request(prepared, key())).path("status").asText()).isEqualTo("PAYABLE");
        onlyOneProviderCall();
    }

    @Test void verifiedWebhookBeforeProviderReturnsPreservesCaptureAcrossPersistenceReplayAndNewPurchase() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        var body = request(prepared, key());
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    String reference = invocation.getArgument(2);
                    assertThat(reference.length()).isLessThanOrEqualTo(40);
                    Map<?, ?> notes = invocation.getArgument(5);
                    String id = notes.get("webAttemptId").toString();
                    long orderId = Long.parseLong(notes.get("orderId").toString());
                    assertThat(notes.get("webReference")).isEqualTo(reference);
                    assertThat(attempt(id).get("state")).isEqualTo("CREATING");
                    assertThat(attempt(id).get("provider_link_id")).isNull();
                    assertThat(jdbc.queryForMap("SELECT gateway_link_id FROM payments WHERE order_id=?", orderId)
                            .get("gateway_link_id")).isNull();
                    // Exercise the real, seven-argument signature-verified webhook boundary
                    // while dispatch is still on the provider stack, before it can save the link.
                    admin.confirmGatewayPayment(orderId, "pay_early", "plink_early", invocation.getArgument(0),
                            id, reference, "INR");
                    assertCaptured(id, "pay_early", "plink_early");
                    assertThat(attempt(id).get("provider_url")).isNull();
                    return new RazorpayService.PaymentLink("plink_early", "https://rzp.io/i/early");
                })
                .thenReturn(new RazorpayService.PaymentLink("plink_later", "https://rzp.io/i/later"));
        var paid = checkout(owner, body);
        String id = paid.path("id").asText();
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        noPaymentUrl(paid);
        assertCaptured(id, "pay_early", "plink_early");
        assertThat(attempt(id).get("provider_url")).isEqualTo("https://rzp.io/i/early");
        verify(notifier, times(1)).orderConfirmed(any());
        var payment = jdbc.queryForMap("SELECT * FROM payments WHERE order_id=?", orderId(id));
        var order = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId(id));
        admin.confirmGatewayPayment(orderId(id), "pay_early", "plink_early",
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE order_id=?", orderId(id))).isEqualTo(payment);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id=?", orderId(id))).isEqualTo(order);
        assertThat(checkout(session(owner.customerId()), body)).isEqualTo(paid);
        assertThat(status(owner, id)).isEqualTo(paid);
        verify(notifier, times(1)).orderConfirmed(any());
        onlyOneProviderCall();
        var cleared = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(cleared.path("version").asLong()).isEqualTo(2);
        assertThat(cleared.path("lines").size()).isZero();
        assertThat(cleared.hasNonNull("date")).isFalse();
        cart(owner, 2, 1);
        var later = checkout(owner, request(prepare(owner, 3), key()));
        assertThat(later.path("status").asText()).isEqualTo("PAYABLE");
        assertThat(later.path("id").asText()).isNotEqualTo(id);
        assertCaptured(id, "pay_early", "plink_early");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId(id))).isEqualTo(1);
        verify(razorpay, times(2)).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    void assertCaptured(String id, String paymentId, String linkId) {
        long orderId = orderId(id);
        var payment = jdbc.queryForMap("SELECT status,gateway_payment_id,gateway_link_id FROM payments WHERE order_id=?", orderId);
        assertThat(payment.get("status")).isEqualTo("GATEWAY_CAPTURED");
        assertThat(payment.get("gateway_payment_id")).isEqualTo(paymentId);
        assertThat(payment.get("gateway_link_id")).isEqualTo(linkId);
        assertThat(attempt(id).get("provider_link_id")).isEqualTo(linkId);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CONFIRMED");
    }

    @Test void ownedStatusIsOpaqueToOtherCustomersMissingSessionsAndAdminPrincipals() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        Browser other = browser();
        var denied = json(call("GET", "/api/checkout/" + id, null, other, false), 404);
        var missing = json(call("GET", "/api/checkout/" + UUID.randomUUID(), null, other, false), 404);
        assertThat(denied).isEqualTo(missing);
        assertThat(denied.path("problems").get(0).path("code").asText()).isEqualTo("CHECKOUT_NOT_FOUND");
        assertThat(call("GET", "/api/checkout/" + id, null, null, false).statusCode()).isEqualTo(401);
        var admin = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/checkout/" + id))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:testpass".getBytes(StandardCharsets.UTF_8)))
                .header("X-Customer-Id", String.valueOf(owner.customerId())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(admin.statusCode()).isEqualTo(401);
        assertThat(status(owner, id).toString()).doesNotContain("customerId", "orderId", "phone", ADDRESS, "plink_");
        onlyOneProviderCall();
    }

    @Test void csrfCookieExpiryAndExactOriginAreEnforcedOnNewRoutes() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        for (String path : List.of("/api/checkout/prepare", "/api/checkout")) {
            String input = path.endsWith("prepare") ? "{\"cartVersion\":1}" : body.toString();
            assertThat(call("POST", path, input, owner, false).statusCode()).isEqualTo(403);
            assertThat(call("POST", path, input, null, false).statusCode()).isEqualTo(403);
            Browser csrfOnly = new Browser(0, "TRANCHE_WEB_CSRF=" + owner.csrf(), owner.csrf());
            assertThat(call("POST", path, input, csrfOnly, true).statusCode()).isEqualTo(401);
            var evil = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Cookie", owner.cookies()).header("X-XSRF-TOKEN", owner.csrf())
                    .header("Origin", "https://evil.example").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(evil.statusCode()).isEqualTo(403);
        }
        jdbc.update("UPDATE web_customer_sessions SET expires_at=? WHERE customer_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        assertThat(call("POST", "/api/checkout", body.toString(), owner, true).statusCode()).isEqualTo(401);
        assertThat(call("POST", "/api/checkout/prepare", "{\"cartVersion\":1}", owner, true).statusCode()).isEqualTo(401);
        noAcceptance(owner);
    }

    @Test void prepareRejectsMalformedUnknownAndCoercedVersions() throws Exception {
        cart(owner, 0, 1);
        for (String body : List.of("{}", "null", "[]", "{", "{\"cartVersion\":\"1\"}",
                "{\"cartVersion\":1.0}", "{\"cartVersion\":0}", "{\"cartVersion\":-1}",
                "{\"cartVersion\":9007199254740992}", "{\"cartVersion\":1,\"amount\":1}"))
            error(call("POST", "/api/checkout/prepare", body, owner, true), 400, "INVALID_REQUEST");
        error(call("POST", "/api/checkout/prepare", "{\"cartVersion\":2}", owner, true), 409, "CART_CONFLICT");
        noAcceptance(owner);
    }

    @Test void checkoutRejectsMissingUnknownMalformedAndCoercedFieldsWithoutSideEffects() throws Exception {
        cart(owner, 0, 1);
        var valid = request(prepare(owner, 1), key());
        var invalid = new ArrayList<String>(List.of("{}", "null", "[]", "{"));
        valid.fieldNames().forEachRemaining(field -> {
            var missing = valid.deepCopy();
            missing.remove(field);
            invalid.add(missing.toString());
            var nulled = valid.deepCopy();
            nulled.putNull(field);
            invalid.add(nulled.toString());
        });
        invalid.add(valid.deepCopy().put("amount", 1).toString());
        invalid.add(valid.deepCopy().put("customerId", owner.customerId()).toString());
        invalid.add(valid.deepCopy().put("cartVersion", "1").toString());
        invalid.add(valid.deepCopy().put("cartVersion", 1.0).toString());
        invalid.add(valid.deepCopy().put("cartVersion", 0).toString());
        invalid.add(valid.deepCopy().put("cartVersion", 9007199254740992L).toString());
        invalid.add(valid.deepCopy().put("name", 123).toString());
        invalid.add(valid.deepCopy().put("address", true).toString());
        for (String body : invalid) error(call("POST", "/api/checkout", body, owner, true), 400, "INVALID_REQUEST");
        noAcceptance(owner);
    }

    @Test void checkoutRejectsBadKeyNameAddressAndPreferenceBounds() throws Exception {
        cart(owner, 0, 1);
        var valid = request(prepare(owner, 1), key());
        Map<String, List<String>> badValues = Map.of(
                "idempotencyKey", List.of("short", "a".repeat(81), "invalid/key-here!", "é".repeat(16)),
                "name", List.of(" ", "n".repeat(101), "bad\nname", "bad\u0000name"),
                "address", List.of("short", " ", "a".repeat(2001), "bad\naddress here", "bad\u007faddress here"),
                "deliveryPreference", List.of("gate", "PICKUP", "GATE ", ""),
                "loafPreference", List.of("whole", "HALF", "WHOLE ", ""));
        for (var entry : badValues.entrySet())
            for (String value : entry.getValue())
                error(call("POST", "/api/checkout", valid.deepCopy().put(entry.getKey(), value).toString(), owner, true),
                        400, "INVALID_REQUEST");
        noAcceptance(owner);
    }

    @Test void validBoundaryLengthsAreTrimmedAndAlternatePreferencesAccepted() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), "a".repeat(80)).put("name", "  " + "n".repeat(100) + "  ")
                .put("address", "  " + "a".repeat(2000) + "  ").put("deliveryPreference", "DOOR").put("loafPreference", "SLICED");
        String id = checkout(owner, body).path("id").asText();
        JsonNode snapshot = mapper.readTree(attempt(id).get("snapshot").toString());
        assertThat(snapshot.findValue("name").asText()).isEqualTo("n".repeat(100));
        assertThat(snapshot.findValue("address").asText()).isEqualTo("a".repeat(2000));
        assertThat(snapshot.findValue("deliveryPreference").asText()).isEqualTo("DOOR");
        assertThat(snapshot.findValue("loafPreference").asText()).isEqualTo("SLICED");
        onlyOneProviderCall();
    }

    @Test void creditGatingPreservesQuoteAndNeverConsumesCreditIncludingZeroTotal() throws Exception {
        jdbc.update("UPDATE customers SET pricing_override=100,free_delivery=true,credit_balance=70 WHERE id=?", owner.customerId());
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        assertThat(prepared.path("quote").path("creditApplied").decimalValue()).isEqualByComparingTo("70");
        assertThat(prepared.path("quote").path("totalAmount").decimalValue()).isEqualByComparingTo("30");
        error(call("POST", "/api/checkout", request(prepared, key()).toString(), owner, true), 409, "CREDIT_SUPPORT_REQUIRED");
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200).path("quote")).isEqualTo(prepared.path("quote"));
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM customers WHERE id=?", BigDecimal.class, owner.customerId())).isEqualByComparingTo("70");
        jdbc.update("UPDATE customers SET credit_balance=1000 WHERE id=?", owner.customerId());
        var zero = prepare(owner, 1);
        assertThat(zero.path("quote").path("totalAmount").decimalValue()).isZero();
        error(call("POST", "/api/checkout", request(zero, key()).toString(), owner, true), 409, "CREDIT_SUPPORT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM customers WHERE id=?", BigDecimal.class, owner.customerId())).isEqualByComparingTo("1000");
        noAcceptance(owner);
    }

    @Test void positiveUnusedCreditStillRequiresSupport() throws Exception {
        jdbc.update("UPDATE customers SET pricing_override=0,free_delivery=true,credit_balance=10 WHERE id=?", owner.customerId());
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        assertThat(prepared.path("quote").path("creditApplied").decimalValue()).isZero();
        error(call("POST", "/api/checkout", request(prepared, key()).toString(), owner, true), 409, "CREDIT_SUPPORT_REQUIRED");
        noAcceptance(owner);
    }

    @Test void zeroPayableWithoutCreditRequiresSupportNotFakePaid() throws Exception {
        jdbc.update("UPDATE customers SET pricing_override=0,free_delivery=true,credit_balance=0 WHERE id=?", owner.customerId());
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        assertThat(prepared.path("quote").path("totalAmount").decimalValue()).isZero();
        error(call("POST", "/api/checkout", request(prepared, key()).toString(), owner, true), 409, "ZERO_PAYABLE_SUPPORT_REQUIRED");
        noAcceptance(owner);
    }

    @Test void gatewayOrWebhookMisconfigurationFailsClosedWithoutQrFallback() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        when(razorpay.isGatewayMode()).thenReturn(false);
        error(call("POST", "/api/checkout", body.toString(), owner, true), 503, "PAYMENT_UNAVAILABLE");
        when(razorpay.isGatewayMode()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(false);
        error(call("POST", "/api/checkout", body.toString(), owner, true), 503, "PAYMENT_UNAVAILABLE");
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.webhookConfigured()).thenReturn(false);
        error(call("POST", "/api/checkout", body.toString(), owner, true), 503, "PAYMENT_UNAVAILABLE");
        noAcceptance(owner);
    }

    @Test void repricingRejectsOldAcceptanceTokenWithoutChangingCartVersion() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        jdbc.update("UPDATE menu_items SET price=300 WHERE id=?", itemId);
        error(call("POST", "/api/checkout", body.toString(), owner, true), 409, "QUOTE_CHANGED");
        var current = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(current.path("version").asLong()).isEqualTo(1);
        assertThat(current.path("quote").path("listSubtotal").decimalValue()).isEqualByComparingTo("300");
        noAcceptance(owner);
    }

        @Test void expiredQuoteRejectsFirstAcceptanceWithoutOrderPaymentOrProviderDispatch() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        jdbc.update("UPDATE web_checkout_quotes SET expires_at=? WHERE customer_id=?",
            Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        error(call("POST", "/api/checkout", body.toString(), owner, true), 409, "QUOTE_EXPIRED");
        noAcceptance(owner);
        }

        @Test void exactSameKeyReplayIgnoresExpiredQuoteButExpiredAttemptNeverExposesLinkOrRedispatches() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        var original = checkout(owner, body);
        String id = original.path("id").asText();
        jdbc.update("UPDATE web_checkout_quotes SET expires_at=? WHERE customer_id=?",
            Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        assertThat(checkout(owner, body)).isEqualTo(original);
        jdbc.update("UPDATE web_checkout_attempts SET expires_at=? WHERE id=?",
            Timestamp.from(Instant.now().minusSeconds(1)), id);
        var expired = checkout(session(owner.customerId()), body);
        assertThat(expired.path("id").asText()).isEqualTo(id);
        assertThat(expired.path("status").asText()).isEqualTo("EXPIRED");
        noPaymentUrl(expired);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId(id)))
            .isNotIn("CONFIRMED", "GATEWAY_CAPTURED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        onlyOneProviderCall();
        }

    @Test void changedCartAndOtherCustomerTokenCannotBeAccepted() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        var body = request(prepared, key());
        cart(owner, 1, 2);
        error(call("POST", "/api/checkout", body.toString(), owner, true), 409, "CART_CONFLICT");
        Browser other = browser();
        cart(other, 0, 1);
        assertThat(call("POST", "/api/checkout", request(prepared, key()).toString(), other, true).statusCode()).isEqualTo(409);
        noAcceptance(owner);
        noAcceptance(other);
    }

    @Test void currentlyRetiredBasketCannotReserveOrderOrPayment() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        jdbc.update("UPDATE menu_items SET active=false WHERE id=?", itemId);
        assertThat(call("POST", "/api/checkout", body.toString(), owner, true).statusCode()).isIn(400, 409);
        noAcceptance(owner);
    }

    @Test void exactIdempotentReplayReturnsOriginalEvenAfterCartAndPricesChange() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        var original = checkout(owner, body);
        cart(owner, 1, 2);
        jdbc.update("UPDATE menu_items SET price=300 WHERE id=?", itemId);
        assertThat(checkout(session(owner.customerId()), body)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        onlyOneProviderCall();
    }

    @Test void sameKeyWithDifferentBindingConflictsInsteadOfReturningOrMutatingOriginal() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        var original = checkout(owner, body);
        List<ObjectNode> changes = List.of(body.deepCopy().put("cartVersion", 2),
                body.deepCopy().put("quoteToken", crypto.token()), body.deepCopy().put("name", "Other Name"),
                body.deepCopy().put("address", "Another valid address, Gurugram"),
                body.deepCopy().put("deliveryPreference", "IN_PERSON"), body.deepCopy().put("loafPreference", "SLICED"));
        for (var changed : changes)
            error(call("POST", "/api/checkout", changed.toString(), owner, true), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(status(owner, original.path("id").asText())).isEqualTo(original);
        onlyOneProviderCall();
    }

    @Test void newKeyCannotCreateAnotherActiveAttemptForCustomer() throws Exception {
        cart(owner, 0, 1);
        checkout(owner, request(prepare(owner, 1), key()));
        // Re-prepare: accepted reservations/offers must not accidentally make this a stale-quote test.
        var fresh = prepare(owner, 1);
        error(call("POST", "/api/checkout", request(fresh, key()).toString(), owner, true), 409, "CHECKOUT_IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        onlyOneProviderCall();
    }

    @Test void concurrentDuplicateKeyCreatesExactlyOneOrderPaymentAndProviderLink() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        var results = race(() -> call("POST", "/api/checkout", body.toString(), owner, true),
                () -> call("POST", "/api/checkout", body.toString(), owner, true));
        var first = json(results.get(0), 200);
        var second = json(results.get(1), 200);
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        assertThat(first.path("status").asText()).isIn("CREATING", "PAYABLE");
        assertThat(second.path("status").asText()).isIn("CREATING", "PAYABLE");
        assertThat(status(owner, first.path("id").asText()).path("status").asText()).isEqualTo("PAYABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId(first.path("id").asText()))).isEqualTo(1);
        onlyOneProviderCall();
    }

    @Test void competingCustomersNearDailyCapacityHaveOneWinnerAndNoOrphanPayment() throws Exception {
        Browser other = browser();
        Browser reserved = browser();
        long reservation = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO orders(customer_id,channel,status,fulfillment_type,source,total_amount,delivery_date)
            VALUES (?,'WHATSAPP','PENDING_CONFIRMATION','DELIVERY','RETAIL',1200,?) RETURNING id
            """, Long.class, reserved.customerId(), date));
        jdbc.update("INSERT INTO order_items(order_id,menu_item_id,quantity,unit_price,subtotal) VALUES (?,?,6,200,1200)",
            reservation, itemId);
        cart(owner, 0, 5);
        cart(other, 0, 5);
        var firstBody = request(prepare(owner, 1), key());
        var secondBody = request(prepare(other, 1), key());
        var results = race(() -> call("POST", "/api/checkout", firstBody.toString(), owner, true),
                () -> call("POST", "/api/checkout", secondBody.toString(), other, true));
        assertThat(results.stream().map(response -> response.statusCode()).toList()).containsExactlyInAnyOrder(200, 409);
        for (var response : results)
            if (response.statusCode() == 409) error(response, 409, "FULLY_BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id IN (?,?)", Integer.class, owner.customerId(), other.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(i.quantity),0) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.customer_id IN (?,?)",
            Integer.class, owner.customerId(), other.customerId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments p JOIN orders o ON o.id=p.order_id WHERE o.customer_id IN (?,?)",
                Integer.class, owner.customerId(), other.customerId())).isEqualTo(1);
        onlyOneProviderCall();
    }

    @Test void webCheckoutAndLegacyConfirmationRaceForSameCapacityWithoutOverselling() throws Exception {
        record LegacyDraft(long customerId, long orderId) {}
        record ReservationResult(HttpResponse<String> response, String legacyCode) {}
        // A distinct legacy customer/draft avoids customer locking accidentally serializing
        // the contenders. Commit fixtures before either transaction attempts reservation.
        var legacy = Objects.requireNonNull(tx.execute(transaction -> {
            long customerId = Objects.requireNonNull(jdbc.queryForObject("INSERT INTO customers(phone,marketing_opt_in) VALUES (?,false) RETURNING id",
                    Long.class, "9197" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000))));
            long draftId = Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO orders(customer_id,channel,status,fulfillment_type,source,total_amount,delivery_date)
                        VALUES (?,'WHATSAPP','DRAFT','DELIVERY','RETAIL',2200,?) RETURNING id
                    """, Long.class, customerId, date));
                    jdbc.update("INSERT INTO order_items(order_id,menu_item_id,quantity,unit_price,subtotal) VALUES (?,?,11,200,2200)",
                    draftId, itemId);
            return new LegacyDraft(customerId, draftId);
        }));
        assertThat(legacy).isNotNull();
        customerIds.add(legacy.customerId());
        assertThat(legacy.customerId()).isNotEqualTo(owner.customerId());
        cart(owner, 0, 5);
        var body = request(prepare(owner, 1), key());
        var results = race(
                () -> new ReservationResult(call("POST", "/api/checkout", body.toString(), owner, true), null),
                () -> {
                    try {
                        // Catch outside execute: a rejected confirmation must roll back fully.
                        tx.executeWithoutResult(transaction ->
                                orderService.confirm(orders.findById(legacy.orderId()).orElseThrow()));
                        return new ReservationResult(null, "ACCEPTED");
                    } catch (CapacityReservationException rejected) {
                        return new ReservationResult(null, rejected.getMessage());
                    }
                });
        var web = results.get(0).response();
        String legacyCode = results.get(1).legacyCode();
        assertThat(web.statusCode()).withFailMessage(web.body()).isIn(200, 409);
        boolean webWon = web.statusCode() == 200;
        if (webWon) {
            assertThat(json(web, 200).path("status").asText()).isEqualTo("PAYABLE");
            assertThat(legacyCode).isEqualTo("FULLY_BOOKED");
        } else {
            error(web, 409, "FULLY_BOOKED");
            assertThat(legacyCode).isEqualTo("ACCEPTED");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, legacy.orderId()))
                .isEqualTo(webWon ? "DRAFT" : "PENDING_CONFIRMATION");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders WHERE customer_id IN (?,?) AND status='PENDING_CONFIRMATION'
                """, Integer.class, owner.customerId(), legacy.customerId())).isEqualTo(1);
        int booked = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT coalesce(sum(i.quantity),0) FROM order_items i JOIN orders o ON o.id=i.order_id
                WHERE o.customer_id IN (?,?) AND o.delivery_date=? AND o.status='PENDING_CONFIRMATION'
                """, Integer.class, owner.customerId(), legacy.customerId(), date));
        assertThat(booked).isEqualTo(webWon ? 5 : 11).isLessThanOrEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId()))
                .isEqualTo(webWon ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId()))
                .isEqualTo(webWon ? 1 : 0);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments p JOIN orders o ON o.id=p.order_id WHERE o.customer_id IN (?,?)
                """, Integer.class, owner.customerId(), legacy.customerId())).isEqualTo(webWon ? 1 : 0);
        verify(razorpay, times(webWon ? 1 : 0)).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { ready.countDown(); assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return second.call(); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(35, TimeUnit.SECONDS), b.get(35, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void providerTimeoutIsDurableUnknownAndNeitherReplayNorPollingRetriesDispatch() throws Exception {
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new java.net.http.HttpTimeoutException("ambiguous provider dispatch"));
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        var result = checkout(owner, body);
        assertThat(result.path("status").asText()).isEqualTo("UNKNOWN");
        noPaymentUrl(result);
        assertThat(attempt(result.path("id").asText()).get("state")).isEqualTo("UNKNOWN");
        assertThat(checkout(owner, body)).isEqualTo(result);
        assertThat(status(owner, result.path("id").asText())).isEqualTo(result);
        error(call("POST", "/api/checkout", request(prepare(owner, 1), key()).toString(), owner, true), 409, "CHECKOUT_IN_PROGRESS");
        onlyOneProviderCall();
    }

    @Test void acceptanceCommitFailureRollsBackEverythingAndDoesNotDispatch() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        failNextCapacityTransactionAtCommit();
        error(call("POST", "/api/checkout", body.toString(), owner, true), 503, "UNAVAILABLE");
        noAcceptance(owner);
        assertThat(jdbc.queryForObject("SELECT version FROM web_carts WHERE customer_id=?",
                Long.class, owner.customerId())).isEqualTo(1);
        assertThat(checkout(owner, body).path("status").asText()).isEqualTo("PAYABLE");
        onlyOneProviderCall();
    }

    @Test void providerPersistenceCommitFailureBecomesUnknownWithoutRedispatchAndCanReconcile() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        var body = request(prepared, key());
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    failNextCapacityTransactionAtCommit();
                    return new RazorpayService.PaymentLink("plink_commit_lost", "https://rzp.io/i/commit_lost");
                });
        var unknown = checkout(owner, body);
        String id = unknown.path("id").asText();
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        noPaymentUrl(unknown);
        assertThat(attempt(id).get("state")).isEqualTo("UNKNOWN");
        assertThat(attempt(id).get("provider_link_id")).isNull();
        assertThat(jdbc.queryForMap("SELECT gateway_link_id FROM payments WHERE order_id=?", orderId(id))
                .get("gateway_link_id")).isNull();
        assertThat(checkout(owner, body)).isEqualTo(unknown);
        admin.confirmGatewayPayment(orderId(id), "pay_commit_lost", "plink_commit_lost",
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        assertCaptured(id, "pay_commit_lost", "plink_commit_lost");
        assertThat(status(owner, id).path("status").asText()).isEqualTo("PAID");
        onlyOneProviderCall();
    }

    private void failNextCapacityTransactionAtCommit() {
        CapacityLock target = AopTestUtils.getUltimateTargetObject(Objects.requireNonNull(capacity));
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        // A transaction-manager exception here falsely claims commit has already
                        // failed and bypasses Spring's ordinary rollback path. Model a database
                        // callback failure before commit instead, as the cutoff tests also do.
                        throw new CannotAcquireLockException("mock checkout commit failure");
                    }
                });
            }
            return null;
        }).when(target).acquire();
    }

    @Test void capacityLockTimeoutReturnsSafe503WithNoPartialAcceptanceAndAllowsWholeRequestRetry() throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(23734322354538565)");
                }
                error(call("POST", "/api/checkout", body.toString(), owner, true), 503, "UNAVAILABLE");
                noAcceptance(owner);
            } finally {
                connection.rollback();
            }
        }
        assertThat(checkout(owner, body).path("status").asText()).isEqualTo("PAYABLE");
        onlyOneProviderCall();
    }

    @Test void cancellationDuringProviderDispatchNeverExposesUrlOrResurrectsOrderAndRevokesOutsideTransaction() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        var body = request(prepared, key());
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Map<?, ?> notes = invocation.getArgument(5);
                    long orderId = Long.parseLong(notes.get("orderId").toString());
                    tx.executeWithoutResult(transaction -> {
                        capacity.acquire();
                        jdbc.update("UPDATE orders SET status='CANCELLED',cutoff_cancelled=true WHERE id=?", orderId);
                    });
                    return new RazorpayService.PaymentLink("plink_cancel_race", "https://rzp.io/i/cancel_race");
                });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(razorpay).cancelPaymentLink("plink_cancel_race");
        var cancelled = checkout(owner, body);
        String id = cancelled.path("id").asText();
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        noPaymentUrl(cancelled);
        verify(razorpay).cancelPaymentLink("plink_cancel_race");
        assertThat(status(owner, id).path("status").asText()).isEqualTo("CANCELLED");
        verify(razorpay).cancelPaymentLink("plink_cancel_race");
        admin.confirmGatewayPayment(orderId(id), "pay_cancel_race", "plink_cancel_race",
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId(id)))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId(id)))
                .isEqualTo("REVIEW_REQUIRED");
        noPaymentUrl(status(owner, id));
        verify(notifier, never()).orderConfirmed(any());
        onlyOneProviderCall();
    }

    @Test void failedExpiryRevokeRetainsBindingForRetryButSuccessfulRevokeIsNotRepeatedByPolling() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        String link = attempt(id).get("provider_link_id").toString();
        var cancellationCalls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (cancellationCalls.incrementAndGet() == 1)
                throw new java.net.http.HttpTimeoutException("mock revoke timeout");
            return null;
        }).when(razorpay).cancelPaymentLink(link);
        jdbc.update("UPDATE web_checkout_attempts SET expires_at=? WHERE id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), id);
        var expired = status(owner, id);
        assertThat(expired.path("status").asText()).isEqualTo("EXPIRED");
        noPaymentUrl(expired);
        var failed = jdbc.queryForMap("SELECT gateway_link_id,gateway_link_url FROM payments WHERE order_id=?", orderId(id));
        assertThat(failed.get("gateway_link_id")).isEqualTo(link);
        assertThat(failed.get("gateway_link_url")).isNotNull();
        assertThat(status(owner, id).path("status").asText()).isEqualTo("CANCELLED");
        var revoked = jdbc.queryForMap("SELECT gateway_link_id,gateway_link_url FROM payments WHERE order_id=?", orderId(id));
        assertThat(revoked.get("gateway_link_id")).isEqualTo(link);
        assertThat(revoked.get("gateway_link_url")).isNull();
        noPaymentUrl(status(owner, id));
        assertThat(cancellationCalls).hasValue(2);
        verify(razorpay, times(2)).cancelPaymentLink(link);
        onlyOneProviderCall();
    }

    @Test void customerCancelRetiresLinkOutsideTransactionKeepsBasketAndAllowsNewCheckout() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        String link = attempt(id).get("provider_link_id").toString();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(razorpay).cancelPaymentLink(link);
        assertThat(call("POST", "/api/checkout/" + id + "/cancel", null, owner, false).statusCode()).isEqualTo(403);
        error(call("POST", "/api/checkout/" + id + "/cancel", null, browser(), true), 404, "CHECKOUT_NOT_FOUND");
        verify(razorpay, never()).cancelPaymentLink(any());
        var cancelled = json(call("POST", "/api/checkout/" + id + "/cancel", null, owner, true), 200);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        noPaymentUrl(cancelled);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId(id))).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT gateway_link_url FROM payments WHERE order_id=?", String.class, orderId(id))).isNull();
        assertThat(json(call("POST", "/api/checkout/" + id + "/cancel", null, owner, true), 200).path("status").asText())
                .isEqualTo("CANCELLED");
        verify(razorpay, times(1)).cancelPaymentLink(link);
        var basket = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(basket.path("lines")).hasSize(1);
        String next = checkout(owner, request(prepare(owner, basket.path("version").asLong()), key())).path("id").asText();
        assertThat(status(owner, next).path("status").asText()).isEqualTo("PAYABLE");
    }

    @Test void customerCancelRefusesPaidOrUnconfirmedLinksWithoutCancellingTheOrder() throws Exception {
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        String id = checkout(owner, request(prepared, key())).path("id").asText();
        String link = attempt(id).get("provider_link_id").toString();
        doThrow(new java.net.http.HttpTimeoutException("mock")).when(razorpay).cancelPaymentLink(link);
        when(razorpay.fetchPaymentLink(link)).thenReturn(new RazorpayService.LinkState("paid", BigDecimal.ONE, "pay_mid_cancel"));
        error(call("POST", "/api/checkout/" + id + "/cancel", null, owner, true), 409, "CANCEL_UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId(id)))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(status(owner, id).path("paymentUrl").asText()).startsWith("https://rzp.io/");
        admin.confirmGatewayPayment(orderId(id), "pay_mid_cancel", link,
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        error(call("POST", "/api/checkout/" + id + "/cancel", null, owner, true), 409, "ALREADY_PAID");
        verify(razorpay, times(1)).cancelPaymentLink(link);
    }

    @Test void customerCancelAcceptsALinkTheProviderAlreadyRetiredWithoutPayment() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        String link = attempt(id).get("provider_link_id").toString();
        doThrow(new IllegalStateException("already cancelled")).when(razorpay).cancelPaymentLink(link);
        when(razorpay.fetchPaymentLink(link)).thenReturn(new RazorpayService.LinkState("cancelled", BigDecimal.ZERO, null));
        assertThat(json(call("POST", "/api/checkout/" + id + "/cancel", null, owner, true), 200).path("status").asText())
                .isEqualTo("CANCELLED");
    }

    @Test void providerTimeoutUnknownIsReconciledByVerifiedWebhookAndAllowsFutureCheckout() throws Exception {
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new java.net.http.HttpTimeoutException("provider accepted before response was lost"))
                .thenReturn(new RazorpayService.PaymentLink("plink_after_timeout", "https://rzp.io/i/after_timeout"));
        cart(owner, 0, 1);
        var prepared = prepare(owner, 1);
        var body = request(prepared, key());
        var unknown = checkout(owner, body);
        String id = unknown.path("id").asText();
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        noPaymentUrl(unknown);
        assertThat(attempt(id).get("state")).isEqualTo("UNKNOWN");
        assertThat(attempt(id).get("provider_link_id")).isNull();
        cart(owner, 1, 2);
        admin.confirmGatewayPayment(orderId(id), "pay_reconciled", "plink_reconciled",
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        assertCaptured(id, "pay_reconciled", "plink_reconciled");
        var edited = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(edited.path("version").asLong()).isEqualTo(2);
        assertThat(edited.path("lines").get(0).path("quantity").asInt()).isEqualTo(2);
        var paid = status(owner, id);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        noPaymentUrl(paid);
        verify(notifier, times(1)).orderConfirmed(any());
        assertThat(checkout(owner, body)).isEqualTo(paid);
        admin.confirmGatewayPayment(orderId(id), "pay_reconciled", "plink_reconciled",
                prepared.path("quote").path("totalAmount").decimalValue(), id, "web_" + id, "INR");
        assertThat(status(owner, id)).isEqualTo(paid);
        onlyOneProviderCall();
        var later = checkout(owner, request(prepare(owner, 2), key()));
        assertThat(later.path("status").asText()).isEqualTo("PAYABLE");
        assertThat(later.path("id").asText()).isNotEqualTo(id);
        assertCaptured(id, "pay_reconciled", "plink_reconciled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId(id))).isEqualTo(1);
        verify(razorpay, times(2)).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void agedCreatingCrashBecomesUnknownWithoutASecondProviderLink() throws Exception {
        assertCrashedDispatchBecomesUnknown("CREATING");
    }

    @Test void agedReadyCrashBecomesUnknownWithoutASecondProviderLink() throws Exception {
        assertCrashedDispatchBecomesUnknown("READY");
    }

    void assertCrashedDispatchBecomesUnknown(String state) throws Exception {
        cart(owner, 0, 1);
        var body = request(prepare(owner, 1), key());
        String id = checkout(owner, body).path("id").asText();
        // Simulate a committed reservation whose process died without durable provider
        // response persistence. No sleeping, no redispatch, even from an exact POST replay.
        tx.executeWithoutResult(transaction -> {
            jdbc.update("""
                    UPDATE web_checkout_attempts SET state=?,updated_at=?,provider_link_id=NULL,provider_url=NULL WHERE id=?
                    """, state, Timestamp.from(Instant.now().minusSeconds(300)), id);
            jdbc.update("UPDATE payments SET gateway_link_id=NULL,gateway_link_url=NULL WHERE order_id=?", orderId(id));
        });
        var unknown = status(owner, id);
        assertThat(unknown.path("status").asText()).isEqualTo("UNKNOWN");
        noPaymentUrl(unknown);
        assertThat(attempt(id).get("state")).isEqualTo("UNKNOWN");
        assertThat(checkout(session(owner.customerId()), body)).isEqualTo(unknown);
        assertThat(status(owner, id)).isEqualTo(unknown);
        error(call("POST", "/api/checkout", request(prepare(owner, 1), key()).toString(), owner, true),
                409, "CHECKOUT_IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId(id))).isEqualTo(1);
        assertThat(attempt(id).get("provider_link_id")).isNull();
        onlyOneProviderCall();
    }

    @Test void unsafeProviderUrlsBecomeUnknownAndAreNeverReturnedOrRetried() throws Exception {
        List<String> unsafe = List.of("http://rzp.io/i/plain", "https://rzp.io.evil.example/i/fake",
                "https://user@rzp.io/i/credentials", "https://rzp.io:444/i/port", "https://rzp.io/i/link#fragment",
            "javascript:alert(1)", "https://evil.example/pay", "https:/pay", "https:payment",
            "//rzp.io/i/schemeless", "https://rzp.io./i/dot", "https://rzp.io%2eevil.example/pay",
            "https://rzp.io\\@evil.example/pay", "https://rzp.io/i/line\nbreak");
        var index = new java.util.concurrent.atomic.AtomicInteger();
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation ->
                new RazorpayService.PaymentLink("plink_unsafe_" + index.get(), unsafe.get(index.getAndIncrement())));
        for (String url : unsafe) {
            Browser customer = browser();
            cart(customer, 0, 1);
            var body = request(prepare(customer, 1), key());
            var result = checkout(customer, body);
            assertThat(result.path("status").asText()).as(url).isEqualTo("UNKNOWN");
            noPaymentUrl(result);
            assertThat(result.toString()).doesNotContain(url);
            assertThat(status(customer, result.path("id").asText())).isEqualTo(result);
            assertThat(checkout(customer, body)).isEqualTo(result);
        }
        verify(razorpay, times(unsafe.size())).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
    }

    @Test void expiredAttemptSuppressesLinkAndIsNeverMistakenForPaid() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        jdbc.update("UPDATE web_checkout_attempts SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), id);
        var expired = status(owner, id);
        assertThat(expired.path("status").asText()).isEqualTo("EXPIRED");
        noPaymentUrl(expired);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId(id)))
                .isNotIn("CONFIRMED", "GATEWAY_CAPTURED");
        onlyOneProviderCall();
    }

    @Test void cutoffCancelledOrderCannotBecomePaidEvenIfPaymentLaterShowsCaptured() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        long orderId = orderId(id);
        jdbc.update("UPDATE orders SET status='CANCELLED',cutoff_cancelled=true WHERE id=?", orderId);
        var cancelled = status(owner, id);
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        noPaymentUrl(cancelled);
        jdbc.update("UPDATE payments SET status='GATEWAY_CAPTURED',gateway_payment_id='pay_late' WHERE order_id=?", orderId);
        var late = status(owner, id);
        assertThat(late.path("status").asText()).isIn("CANCELLED", "REVIEW_REQUIRED");
        noPaymentUrl(late);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        onlyOneProviderCall();
    }

    @Test void browserReturnParametersCannotMarkPaidButVerifiedPaymentAndLiveOrderCan() throws Exception {
        cart(owner, 0, 1);
        String id = checkout(owner, request(prepare(owner, 1), key())).path("id").asText();
        var claimed = json(call("GET", "/api/checkout/" + id + "?razorpay_payment_id=pay_forged&status=paid", null, owner, false), 200);
        assertThat(claimed.path("status").asText()).isEqualTo("PAYABLE");
        long orderId = orderId(id);
        jdbc.update("UPDATE payments SET status='GATEWAY_CAPTURED',gateway_payment_id='pay_verified_fixture' WHERE order_id=?", orderId);
        jdbc.update("UPDATE orders SET status='CONFIRMED' WHERE id=?", orderId);
        var paid = status(owner, id);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        noPaymentUrl(paid);
        onlyOneProviderCall();
    }
}