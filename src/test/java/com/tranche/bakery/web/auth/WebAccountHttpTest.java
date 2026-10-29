package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tranche.bakery.admin.AdminService;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.web.shop.StorefrontDtos;
import com.tranche.bakery.whatsapp.CustomerNotifier;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real PostgreSQL, security filters and cookie-pair identity; all providers are mocked. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "server.forward-headers-strategy=none",
        "bakery.web.enabled=true", "bakery.web.checkout.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.cookie.secure=false", "bakery.cleanup.enabled=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bakery.web.allowed-origins=https://tranchebakery.in", "bakery.order.daily-capacity=15",
        "bakery.batch-discount.enabled=false",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout='20s'"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebAccountHttpTest {
    private static final String ADDRESS = "Flat 42, Account Lane, Gurugram";
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @Autowired AdminService admin;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean CustomerNotifier notifier;
    @MockitoBean RazorpayService razorpay;
    private final List<Long> customerIds = new ArrayList<>();
    private Long categoryId;
    private Long itemId;
    private Browser owner;
    private LocalDate date;

    record Browser(long customerId, Cookie[] cookies, String csrf) { }
    record Attempt(String id, long orderId) { }

    @BeforeEach void setup() {
        categoryId = jdbc.queryForObject("INSERT INTO menu_categories(name,display_order,active) VALUES (?,0,true) RETURNING id",
                Long.class, "Web-account-" + UUID.randomUUID());
        itemId = jdbc.queryForObject("INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,'Historical account loaf',200,0,true) RETURNING id",
                Long.class, categoryId);
        owner = browser();
        date = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    @AfterEach void cleanup() {
        for (Long id : customerIds) {
            jdbc.update("DELETE FROM alerts WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM payments WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE customer_id=?)", id);
            jdbc.update("DELETE FROM web_checkout_attempts WHERE customer_id=?", id);
            jdbc.update("DELETE FROM orders WHERE customer_id=?", id);
            jdbc.update("DELETE FROM subscriptions WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
        jdbc.update("DELETE FROM menu_items WHERE category_id=?", categoryId);
        jdbc.update("DELETE FROM menu_categories WHERE id=?", categoryId);
        verifyNoInteractions(whatsapp);
    }

    private Browser browser() {
        var customer = new Customer();
        customer.setPhone("9195" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
        customer.setMarketingOptIn(false);
        customer = customers.saveAndFlush(customer);
        customerIds.add(customer.getId());
        return session(customer.getId());
    }

    private Browser session(long id) {
        String binding = crypto.token(), token = crypto.token(), csrf = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO web_customer_sessions(token_hash,customer_id,browser_hash,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?,?)",
                crypto.hash("session", token), id, crypto.hash("browser", binding), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return new Browser(id, new Cookie[] {new Cookie(AuthCookies.BROWSER, binding),
                new Cookie(AuthCookies.SESSION, token), new Cookie("TRANCHE_WEB_CSRF", csrf)}, csrf);
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Browser browser, boolean csrf, int status) throws Exception {
        if (browser != null) {
            request.cookie(browser.cookies());
            if (csrf) request.header("X-XSRF-TOKEN", browser.csrf());
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
        assertThat(response.getHeader("Location")).isNull();
        return response.getContentAsString().isEmpty() ? mapper.nullNode() : mapper.readTree(response.getContentAsString());
    }

    private ObjectNode profile(long version) {
        return mapper.createObjectNode().put("version", version).put("name", "  Website Name  ")
                .put("address", "  " + ADDRESS + "  ").put("deliveryPreference", "GATE").put("loafPreference", "WHOLE");
    }

    private JsonNode save(ObjectNode body, Browser browser, int status) throws Exception {
        return call(put("/api/account/profile").contentType("application/json").content(body.toString()), browser, true, status);
    }

    private JsonNode detail(Attempt attempt, Browser browser) throws Exception {
        return call(get("/api/account/orders/" + attempt.id()), browser, false, 200);
    }

    private void fields(JsonNode body, String... names) {
        var actual = new ArrayList<String>();
        body.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(names);
    }

    private void problem(JsonNode body, String code) {
        assertThat(body.path("valid").asBoolean(true)).isFalse();
        assertThat(body.path("problems").get(0).path("code").asText()).isEqualTo(code);
        assertThat(body.toString()).doesNotContain(ADDRESS, "SQLException", "java.lang", "plink_", "token_hash");
    }

    private StorefrontDtos.Quote historicalQuote() {
        return new StorefrontDtos.Quote(List.of(new StorefrontDtos.QuoteLine(itemId, "Historical account loaf", 1,
                new BigDecimal("200"), new BigDecimal("200"), null)), new BigDecimal("200"), BigDecimal.ZERO, null,
                BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO, new BigDecimal("200"), 1, date, true, List.of());
    }

    private Attempt seed(Browser browser) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        Long orderId = jdbc.queryForObject("""
                INSERT INTO orders(customer_id,channel,status,fulfillment_type,source,total_amount,delivery_date,delivery_address)
                VALUES (?,'WEB','PENDING_CONFIRMATION','DELIVERY','RETAIL',200,?,?) RETURNING id
                """, Long.class, browser.customerId(), date, ADDRESS);
        jdbc.update("UPDATE orders SET order_number=? WHERE id=?", "ACC-" + orderId, orderId);
        jdbc.update("""
                INSERT INTO payments(order_id,amount,status,provider,gateway_link_id,gateway_link_url)
                VALUES (?,200,'PENDING','RAZORPAY',?,?)
                """, orderId, "plink_" + id, "https://rzp.io/i/" + id);
        var snapshot = mapper.createObjectNode().put("name", "Accepted Name").put("address", ADDRESS)
                .put("deliveryPreference", "DOOR").put("loafPreference", "SLICED");
        snapshot.set("quote", mapper.valueToTree(historicalQuote()));
        jdbc.update("""
                INSERT INTO web_checkout_attempts(id,customer_id,order_id,idempotency_key,request_hash,cart_version,
                quote_token_hash,quote_hash,snapshot,state,provider_reference,provider_link_id,provider_url,expires_at)
                VALUES (?,?,?,?,'request-secret',1,'token-secret','quote-secret',?::jsonb,'PAYABLE',?,?,?,?)
                """, id, browser.customerId(), orderId, UUID.randomUUID().toString(), mapper.writeValueAsString(snapshot),
                "web_" + id, "plink_" + id, "https://rzp.io/i/" + id, Timestamp.from(Instant.now().plusSeconds(3600)));
        return new Attempt(id, orderId);
    }

    @Test void unsetProfileIsExactEmptyStringsAndGetDoesNotCreateDefaults() throws Exception {
        var empty = call(get("/api/account/profile"), owner, false, 200);
        fields(empty, "version", "name", "address", "deliveryPreference", "loafPreference");
        assertThat(empty.path("version").asLong()).isZero();
        for (String field : List.of("name", "address", "deliveryPreference", "loafPreference"))
            assertThat(empty.path(field).asText()).isEmpty();
        jdbc.update("UPDATE customers SET name='Shared Name',delivery_address=? WHERE id=?", ADDRESS, owner.customerId());
        var seeded = call(get("/api/account/profile"), session(owner.customerId()), false, 200);
        assertThat(seeded.path("name").asText()).isEqualTo("Shared Name");
        assertThat(seeded.path("address").asText()).isEqualTo(ADDRESS);
        assertThat(seeded.path("deliveryPreference").asText()).isEmpty();
        assertThat(seeded.path("loafPreference").asText()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        var recovery = call(get("/api/account/checkout"), owner, false, 200);
        fields(recovery, "checkout");
        assertThat(recovery.path("checkout").isNull()).isTrue();
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void legacySuggestionsAreSafeEditableAndNeverBlockHistoryOrWriteOnGet() throws Exception {
        Attempt accepted = seed(owner);
        var historical = detail(accepted, owner);
        // PostgreSQL TEXT cannot contain NUL; other ISO controls remain legitimate legacy fixtures.
        for (String[] legacy : List.of(
                new String[] {"  Shared\r\nName\tExample  ", "  Flat 42,\r\nAccount Lane\u2028Gurugram\u2029India  ",
                        "Shared Name Example", "Flat 42, Account Lane Gurugram India"},
                new String[] {"Bad\u007fName", ADDRESS + "\u0085hidden", "", ""},
                new String[] {" ", "short", "", ""},
                new String[] {"N".repeat(100), "A".repeat(2001), "N".repeat(100), ""},
                new String[] {"Valid Name", "A".repeat(2000), "Valid Name", "A".repeat(2000)})) {
            jdbc.update("UPDATE customers SET name=?,delivery_address=? WHERE id=?", legacy[0], legacy[1], owner.customerId());
            var shared = jdbc.queryForMap("SELECT name,delivery_address FROM customers WHERE id=?", owner.customerId());
            var suggestions = call(get("/api/account/profile"), owner, false, 200);
            fields(suggestions, "version", "name", "address", "deliveryPreference", "loafPreference");
            assertThat(suggestions.path("version").asLong()).isZero();
            assertThat(suggestions.path("name").asText()).isEqualTo(legacy[2]);
            assertThat(suggestions.path("address").asText()).isEqualTo(legacy[3]);
            assertThat(suggestions.path("deliveryPreference").asText()).isEmpty();
            assertThat(suggestions.path("loafPreference").asText()).isEmpty();
            var history = call(get("/api/account/orders"), owner, false, 200);
            assertThat(history.path("orders")).hasSize(1);
            assertThat(history.path("orders").get(0).path("id").asText()).isEqualTo(accepted.id());
            assertThat(detail(accepted, owner)).isEqualTo(historical);
            assertThat(jdbc.queryForMap("SELECT name,delivery_address FROM customers WHERE id=?", owner.customerId())).isEqualTo(shared);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
            // Fill omitted fields and preferences by explicit consent; GET itself never saved.
            var edits = (ObjectNode) suggestions.deepCopy();
            if (edits.path("name").asText().isEmpty()) edits.put("name", "Edited Name");
            if (edits.path("address").asText().isEmpty()) edits.put("address", ADDRESS);
            edits.put("deliveryPreference", "GATE").put("loafPreference", "WHOLE");
            assertThat(save(edits, owner, 200).path("version").asLong()).isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT name,delivery_address FROM customers WHERE id=?", owner.customerId())).isEqualTo(shared);
            jdbc.update("DELETE FROM web_customer_profiles WHERE customer_id=?", owner.customerId());
        }
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void savingIsSeparateConsentAndCannotModifySharedCustomerOrExistingSnapshot() throws Exception {
        jdbc.update("""
                UPDATE customers SET name='Shared Name',delivery_address='Shared old address',pricing_override=175,
                location_lat=28.45,location_lng=77.02,marketing_opt_in=false WHERE id=?
                """, owner.customerId());
        var shared = jdbc.queryForMap("""
                SELECT name,delivery_address,phone,pricing_override,location_lat,location_lng,marketing_opt_in,
                free_delivery,credit_balance,subscription_eligible FROM customers WHERE id=?
                """, owner.customerId());
        Attempt accepted = seed(owner);
        var before = detail(accepted, owner);
        var order = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", accepted.orderId());
        var saved = save(profile(0), owner, 200);
        fields(saved, "version", "name", "address", "deliveryPreference", "loafPreference");
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("name").asText()).isEqualTo("Website Name");
        assertThat(saved.path("address").asText()).isEqualTo(ADDRESS);
        assertThat(call(get("/api/account/profile"), session(owner.customerId()), false, 200)).isEqualTo(saved);
        assertThat(jdbc.queryForMap("""
                SELECT name,delivery_address,phone,pricing_override,location_lat,location_lng,marketing_opt_in,
                free_delivery,credit_balance,subscription_eligible FROM customers WHERE id=?
                """, owner.customerId())).isEqualTo(shared);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id=?", accepted.orderId())).isEqualTo(order);
        assertThat(detail(accepted, owner)).isEqualTo(before);
        problem(save(profile(0), owner, 409), "PROFILE_CONFLICT");
        assertThat(save(profile(1), owner, 200).path("version").asLong()).isEqualTo(2);
        assertThat(call(get("/api/account/profile"), browser(), false, 200).path("version").asLong()).isZero();
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void strictProfileInputsNeverCoerceOrSavePartialFields() throws Exception {
        var valid = profile(0);
        var invalid = new ArrayList<String>(List.of("{}", "null", "[]", "{"));
        valid.fieldNames().forEachRemaining(field -> {
            var missing = valid.deepCopy(); missing.remove(field); invalid.add(missing.toString());
            var nullValue = valid.deepCopy(); nullValue.putNull(field); invalid.add(nullValue.toString());
        });
        for (String version : List.of("\"0\"", "0.0", "-1", "true", "9007199254740991", "9223372036854775808"))
            invalid.add(valid.toString().replace("\"version\":0", "\"version\":" + version));
        for (String name : List.of("", " ", "x".repeat(101), "bad\nname", "bad\tname", "bad\u2028name", "bad\u0000name"))
            invalid.add(valid.deepCopy().put("name", name).toString());
        for (String address : List.of("short", " ".repeat(20), "x".repeat(2001), ADDRESS + "\r\nnext", ADDRESS + "\u2029"))
            invalid.add(valid.deepCopy().put("address", address).toString());
        invalid.add(valid.deepCopy().put("name", 123).toString());
        invalid.add(valid.deepCopy().put("address", false).toString());
        invalid.add(valid.deepCopy().put("deliveryPreference", "gate").toString());
        invalid.add(valid.deepCopy().put("loafPreference", "").toString());
        invalid.add(valid.deepCopy().put("customerId", owner.customerId()).toString());
        for (String body : invalid)
            problem(call(put("/api/account/profile").contentType("application/json").content(body), owner, true, 400), "INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        var boundary = profile(0).put("name", "x".repeat(100)).put("address", "x".repeat(2000))
                .put("deliveryPreference", "IN_PERSON").put("loafPreference", "SLICED");
        save(boundary, owner, 200);
        jdbc.update("UPDATE web_customer_profiles SET version=9007199254740990 WHERE customer_id=?", owner.customerId());
        assertThat(save(profile(9_007_199_254_740_990L).put("deliveryPreference", "DOOR"), owner, 200)
                .path("version").asLong()).isEqualTo(9_007_199_254_740_991L);
        problem(save(profile(9_007_199_254_740_991L), owner, 400), "INVALID_REQUEST");
    }

    @Test void simultaneousFirstInsertAndStaleUpdateEachHaveOneWinner() throws Exception {
        race(0);
        race(1);
        assertThat(call(get("/api/account/profile"), owner, false, 200).path("version").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
    }

    private void race(long version) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        Browser second = session(owner.customerId());
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (Browser browser : List.of(owner, second)) {
                Callable<Integer> task = () -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
                    return mvc.perform(put("/api/account/profile").cookie(browser.cookies()).header("X-XSRF-TOKEN", browser.csrf())
                            .contentType("application/json").content(profile(version).toString())).andReturn().getResponse().getStatus();
                };
                tasks.add(executor.submit(task));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(tasks.get(0).get(25, TimeUnit.SECONDS), tasks.get(1).get(25, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { go.countDown(); executor.shutdownNow(); }
    }

    @Test void accountRequiresCookiePairNotAdminSessionHeadersOrOtherBrowserBinding() throws Exception {
        Attempt accepted = seed(owner);
        List<String> paths = List.of("/api/account/profile", "/api/account/location", "/api/account/orders", "/api/account/checkout",
                "/api/account/orders/" + accepted.id());
        Browser other = browser();
        for (String path : paths) {
            problem(call(get(path), null, false, 401), "INVALID_SESSION");
            problem(call(get(path).with(user("admin").roles("ADMIN")).session(new MockHttpSession())
                    .header("X-Customer-Id", owner.customerId()).header("Authorization", "Basic YWRtaW46dGVzdHBhc3M="), null, false, 401), "INVALID_SESSION");
            problem(call(get(path).cookie(owner.cookies()[1]), null, false, 401), "INVALID_SESSION");
            problem(call(get(path).cookie(owner.cookies()[1], other.cookies()[0]), null, false, 401), "INVALID_SESSION");
            problem(call(get(path).cookie(new Cookie(AuthCookies.SESSION, "spoof"), owner.cookies()[0]), null, false, 401), "INVALID_SESSION");
        }
        var foreign = call(get("/api/account/orders/" + accepted.id()), other, false, 404);
        for (String id : List.of("not-an-id", "x".repeat(32), String.valueOf(accepted.orderId()), "a".repeat(64)))
            assertThat(call(get("/api/account/orders/" + id), other, false, 404)).isEqualTo(foreign);
        problem(foreign, "CHECKOUT_NOT_FOUND");
        assertThat(call(get("/api/account/orders"), other, false, 200).path("orders")).isEmpty();
        assertThat(call(get("/api/account/checkout"), other, false, 200).path("checkout").isNull()).isTrue();
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void csrfExactOriginLogoutExpiryAndIdleStillApply() throws Exception {
        var request = profile(0).toString();
        call(put("/api/account/profile").contentType("application/json").content(request), owner, false, 403);
        call(put("/api/account/profile").contentType("application/json").content(request).header("X-XSRF-TOKEN", "wrong"), owner, false, 403);
        call(put("/api/account/profile").contentType("application/json").content(request).header("Origin", "https://evil.example"), owner, true, 403);
        call(get("/api/account/profile").header("Origin", "https://evil.example"), owner, false, 403);
        save(profile(0), owner, 200);
        call(post("/api/auth/logout"), owner, true, 200);
        problem(call(get("/api/account/profile"), owner, false, 401), "INVALID_SESSION");
        problem(call(put("/api/account/profile").contentType("application/json").content(request), owner, true, 401), "INVALID_SESSION");
        Browser expired = session(owner.customerId());
        jdbc.update("UPDATE web_customer_sessions SET expires_at=? WHERE customer_id=?", Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        problem(call(get("/api/account/orders"), expired, false, 401), "INVALID_SESSION");
        Browser idle = session(owner.customerId());
        jdbc.update("UPDATE web_customer_sessions SET last_seen_at=? WHERE customer_id=?", Timestamp.from(Instant.now().minusSeconds(WebAuthSettings.IDLE_SECONDS + 1)), owner.customerId());
        problem(call(get("/api/account/checkout"), idle, false, 401), "INVALID_SESSION");
    }

    @Test void historyIsTenPerPageStableAtTiesPrivateAndRetailOnlyWithWhatsAppSummaries() throws Exception {
        var included = new ArrayList<String>();
        Instant same = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 12; i++) {
            Attempt attempt = seed(owner);
            included.add(attempt.id());
            jdbc.update("UPDATE web_checkout_attempts SET created_at=? WHERE id=?", Timestamp.from(same), attempt.id());
        }
        included.sort(Comparator.reverseOrder());
        Attempt foreign = seed(browser());
        Attempt wa = seed(owner);
        // orders.created_at is IST wall time: 10:00 IST is 04:30Z, older than every website attempt.
        jdbc.update("UPDATE orders SET channel='WHATSAPP',status='CONFIRMED',total_amount=345,created_at='2026-09-30 10:00:00' WHERE id=?", wa.orderId());
        Attempt foreignWa = seed(browser()); jdbc.update("UPDATE orders SET channel='WHATSAPP' WHERE id=?", foreignWa.orderId());
        Attempt unnumberedWa = seed(owner);
        jdbc.update("UPDATE orders SET channel='WHATSAPP',order_number=NULL WHERE id=?", unnumberedWa.orderId());
        Attempt commercial = seed(owner); jdbc.update("UPDATE orders SET source='COMMERCIAL' WHERE id=?", commercial.orderId());
        // One draft per customer: a numbered WhatsApp draft also proves the website-only DRAFT exclusion.
        Attempt draft = seed(owner); jdbc.update("UPDATE orders SET channel='WHATSAPP',status='DRAFT' WHERE id=?", draft.orderId());
        Long subscriptionId = jdbc.queryForObject("""
            INSERT INTO subscriptions(customer_id,plan_code,plan_name,tier,weekly_price,delivery_charge,
            commitment_weeks,delivery_day,status,created_at,updated_at)
            VALUES (?,'ACCOUNT_TEST','Account test plan','TEST',200,0,4,'FRIDAY','ACTIVE',now(),now()) RETURNING id
            """, Long.class, owner.customerId());
        Attempt subscription = seed(owner); jdbc.update("UPDATE orders SET subscription_id=? WHERE id=?", subscriptionId, subscription.orderId());
        // A plain WEB order without an attempt cannot enter this history.
        jdbc.update("INSERT INTO orders(customer_id,channel,status,source,total_amount) VALUES (?,'WEB','CONFIRMED','RETAIL',123)", owner.customerId());
        var first = call(get("/api/account/orders"), owner, false, 200);
        fields(first, "orders", "page", "hasMore");
        assertThat(first.path("orders")).hasSize(10);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        var actual = new ArrayList<String>();
        for (var row : first.path("orders")) {
            fields(row, "id", "channel", "orderNumber", "createdAt", "deliveryDate", "orderStatus", "status", "totalAmount");
            assertThat(row.path("channel").asText()).isEqualTo("WEB");
            actual.add(row.path("id").asText());
            assertThat(Instant.parse(row.path("createdAt").asText())).isEqualTo(same);
            assertThat(row.path("deliveryDate").asText()).isEqualTo(date.toString());
            assertThat(row.path("totalAmount").decimalValue()).isEqualByComparingTo("200");
        }
        assertThat(actual).containsExactlyElementsOf(included.subList(0, 10));
        var second = call(get("/api/account/orders").param("page", "1"), owner, false, 200);
        assertThat(second.path("orders")).hasSize(3);
        assertThat(second.path("page").asInt()).isEqualTo(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("orders").get(0).path("id").asText()).isEqualTo(included.get(10));
        assertThat(second.path("orders").get(1).path("id").asText()).isEqualTo(included.get(11));
        var whatsapp = second.path("orders").get(2);
        fields(whatsapp, "id", "channel", "orderNumber", "createdAt", "deliveryDate", "orderStatus", "status", "totalAmount");
        assertThat(whatsapp.path("id").isNull()).isTrue();
        assertThat(whatsapp.path("status").isNull()).isTrue();
        assertThat(whatsapp.path("channel").asText()).isEqualTo("WHATSAPP");
        assertThat(whatsapp.path("orderNumber").asText()).isEqualTo("ACC-" + wa.orderId());
        assertThat(Instant.parse(whatsapp.path("createdAt").asText())).isEqualTo(Instant.parse("2026-09-30T04:30:00Z"));
        assertThat(whatsapp.path("deliveryDate").asText()).isEqualTo(date.toString());
        assertThat(whatsapp.path("orderStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(whatsapp.path("totalAmount").decimalValue()).isEqualByComparingTo("345");
        assertThat(second.toString()).doesNotContain(ADDRESS, "plink_", "paymentUrl", wa.id(),
                "\"ACC-" + foreignWa.orderId() + "\"", "\"ACC-" + draft.orderId() + "\"", "\"ACC-" + commercial.orderId() + "\"",
                "\"ACC-" + unnumberedWa.orderId() + "\"");
        assertThat(call(get("/api/account/orders").param("page", "10000"), owner, false, 200).path("orders")).isEmpty();
        assertThat(first.toString()).doesNotContain("customerId", "orderId", "paymentUrl", "fulfillment", ADDRESS,
                "Accepted Name", "phone", "plink_", "token-secret", "request-secret", "web_", foreign.id(), wa.id(), commercial.id(), draft.id(), subscription.id());
        for (Attempt excluded : List.of(foreign, wa, foreignWa, unnumberedWa, commercial, draft, subscription))
            problem(call(get("/api/account/orders/" + excluded.id()), owner, false, 404), "CHECKOUT_NOT_FOUND");
        assertThat(call(get("/api/account/checkout"), owner, false, 200).path("checkout").path("id").asText()).isEqualTo(included.get(0));
        verifyNoInteractions(razorpay, notifier);
    }

    private long subscription(Browser browser, String status, String created, LocalDate start) {
        return jdbc.queryForObject("""
            INSERT INTO subscriptions(customer_id,plan_code,plan_name,tier,weekly_price,delivery_charge,commitment_weeks,
            bonus_weeks,upfront_amount,delivery_day,start_date,end_date,status,created_at,updated_at)
            VALUES (?,'ACCOUNT_TEST','Weekly sourdough','TEST',200,0,4,1,800,'FRIDAY',?,?,?,?::timestamp,now()) RETURNING id
            """, Long.class, browser.customerId(), start, start == null ? null : start.plusWeeks(4), status, created);
    }

    @Test void historyListsStartedSubscriptionsAsOneSummaryRowAmongOrders() throws Exception {
        Attempt wa = seed(owner);
        jdbc.update("UPDATE orders SET channel='WHATSAPP',status='COMPLETED',created_at='2026-09-20 10:00:00' WHERE id=?", wa.orderId());
        LocalDate start = LocalDate.of(2026, 8, 7);
        long completed = subscription(owner, "COMPLETED", "2026-08-01 09:00:00", start);
        for (int week = 1; week <= 3; week++) {
            Attempt delivery = seed(owner);
            jdbc.update("UPDATE orders SET channel='WHATSAPP',status=?,subscription_id=? WHERE id=?",
                    week < 3 ? "COMPLETED" : "CANCELLED", completed, delivery.orderId());
            jdbc.update("INSERT INTO subscription_periods(subscription_id,week_number,delivery_date,order_id,created_at) VALUES (?,?,?,?,now())",
                    completed, week, start.plusWeeks(week - 1), delivery.orderId());
            jdbc.update("DELETE FROM web_checkout_attempts WHERE id=?", delivery.id());
        }
        subscription(owner, "ACTIVE", "2026-10-02 08:00:00", LocalDate.of(2026, 10, 9));
        subscription(owner, "CANCELLED", "2026-09-25 08:00:00", null);    // never paid
        subscription(owner, "PENDING_PAYMENT", "2026-10-03 08:00:00", null);
        subscription(browser(), "ACTIVE", "2026-10-02 08:00:00", LocalDate.of(2026, 10, 9));
        jdbc.update("DELETE FROM web_checkout_attempts WHERE id=?", wa.id());

        var orders = call(get("/api/account/orders"), owner, false, 200).path("orders");
        assertThat(orders).hasSize(3);
        assertThat(orders.get(0).path("channel").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(orders.get(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(orders.get(1).path("channel").asText()).isEqualTo("WHATSAPP");
        fields(orders.get(1), "id", "channel", "orderNumber", "createdAt", "deliveryDate", "orderStatus", "status", "totalAmount");
        var past = orders.get(2);
        fields(past, "id", "channel", "orderNumber", "createdAt", "deliveryDate", "orderStatus", "status", "totalAmount", "subscription");
        assertThat(past.path("id").isNull()).isTrue();
        assertThat(past.path("orderNumber").isNull()).isTrue();
        assertThat(past.path("deliveryDate").isNull()).isTrue();
        assertThat(past.path("orderStatus").isNull()).isTrue();
        assertThat(past.path("status").asText()).isEqualTo("COMPLETED");
        // created_at is IST wall time: 09:00 IST is 03:30Z.
        assertThat(Instant.parse(past.path("createdAt").asText())).isEqualTo(Instant.parse("2026-08-01T03:30:00Z"));
        assertThat(past.path("totalAmount").decimalValue()).isEqualByComparingTo("800");
        var detail = past.path("subscription");
        fields(detail, "planName", "deliveryDay", "startDate", "endDate", "weeks", "delivered");
        assertThat(detail.path("planName").asText()).isEqualTo("Weekly sourdough");
        assertThat(detail.path("deliveryDay").asText()).isEqualTo("FRIDAY");
        assertThat(detail.path("startDate").asText()).isEqualTo("2026-08-07");
        assertThat(detail.path("endDate").asText()).isEqualTo("2026-09-04");
        assertThat(detail.path("weeks").asInt()).isEqualTo(5);
        assertThat(detail.path("delivered").asInt()).isEqualTo(2);
        assertThat(orders.toString()).doesNotContain("\"" + completed + "\"", "ACCOUNT_TEST", "phone", "customerId");
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void pageDoesNotAcceptArraysDuplicatesCoercionOrUnboundedOffsets() throws Exception {
        for (String page : List.of("", "-1", "+1", "01", "1.0", "1e2", " 1", "10001", "99999999999999999999", "[0]", "null"))
            problem(call(get("/api/account/orders").param("page", page), owner, false, 400), "INVALID_REQUEST");
        problem(call(get("/api/account/orders").param("page", "0", "1"), owner, false, 400), "INVALID_REQUEST");
        problem(call(get("/api/account/orders").param("page[]", "0"), owner, false, 400), "INVALID_REQUEST");
    }

    @Test void proxyAddedQueryFieldsAreIgnoredAndNeverSelectAnotherCustomer() throws Exception {
        Attempt mine = seed(owner);
        Attempt theirs = seed(browser());
        // Vercel's host-guarded external rewrite forwards the matched host as a query field.
        var plain = call(get("/api/account/orders").param("host", "www.tranchebakery.in"), owner, false, 200);
        assertThat(plain.path("page").asInt()).isZero();
        assertThat(plain.path("orders")).hasSize(1);
        assertThat(plain.path("orders").get(0).path("id").asText()).isEqualTo(mine.id());
        var paged = call(get("/api/account/orders").param("page", "0").param("customerId", "1")
                .param("host", "tranchebakery.in"), owner, false, 200);
        assertThat(paged.toString()).contains(mine.id()).doesNotContain(theirs.id());
        problem(call(get("/api/account/orders").param("page", "x").param("host", "tranchebakery.in"), owner, false, 400), "INVALID_REQUEST");
        fields(call(get("/api/account/location").param("host", "www.tranchebakery.in"), owner, false, 200), "latitude", "longitude");
    }

    @Test void detailAndRecoveryAreHistoricalSnapshotsNotLiveCatalogCustomerOrSavedProfile() throws Exception {
        Attempt accepted = seed(owner);
        var before = detail(accepted, owner);
        fields(before, "id", "orderNumber", "createdAt", "orderStatus", "status", "paymentUrl", "expiresAt", "fulfillment", "quote");
        fields(before.path("fulfillment"), "name", "address", "deliveryPreference", "loafPreference");
        assertThat(before.path("id").asText()).hasSize(32);
        assertThat(before.path("fulfillment").path("name").asText()).isEqualTo("Accepted Name");
        assertThat(before.path("quote").path("lines").get(0).path("name").asText()).isEqualTo("Historical account loaf");
        save(profile(0), owner, 200);
        jdbc.update("UPDATE menu_items SET name='Renamed live loaf',price=999,active=false WHERE id=?", itemId);
        jdbc.update("UPDATE customers SET name='Shared later',delivery_address='Shared later address' WHERE id=?", owner.customerId());
        assertThat(detail(accepted, session(owner.customerId()))).isEqualTo(before);
        var recovery = call(get("/api/account/checkout"), owner, false, 200);
        assertThat(recovery.path("checkout")).isEqualTo(before);
        assertThat(before.toString()).doesNotContain("customerId", "orderId", "phone", "gateway", "provider", "token-secret", "quote-secret", "request-secret", "idempotency", "plink_", "web_");
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void unsafeOrMismatchedUrlsAreNeverRecoveredAndCapturedLabelAloneIsNotPaid() throws Exception {
        Attempt accepted = seed(owner);
        assertThat(detail(accepted, owner).path("paymentUrl").asText()).isEqualTo("https://rzp.io/i/" + accepted.id());
        for (String url : List.of("https://evil.example/pay", "https://rzp.io.evil.example/pay", "http://rzp.io/pay",
                "https://user@rzp.io/pay", "https://rzp.io:444/pay", "https://rzp.io/pay#fragment", "javascript:alert(1)")) {
            jdbc.update("UPDATE web_checkout_attempts SET provider_url=? WHERE id=?", url, accepted.id());
            assertThat(detail(accepted, owner).path("paymentUrl").isNull()).isTrue();
            assertThat(call(get("/api/account/checkout"), owner, false, 200).path("checkout").path("paymentUrl").isNull()).isTrue();
        }
        jdbc.update("UPDATE web_checkout_attempts SET provider_url=? WHERE id=?", "https://rzp.io/i/safe", accepted.id());
        jdbc.update("UPDATE payments SET gateway_link_id='plink_mismatch' WHERE order_id=?", accepted.orderId());
        assertThat(detail(accepted, owner).path("paymentUrl").isNull()).isTrue();
        jdbc.update("UPDATE payments SET status='GATEWAY_CAPTURED' WHERE order_id=?", accepted.orderId());
        jdbc.update("UPDATE orders SET status='CONFIRMED' WHERE id=?", accepted.orderId());
        assertThat(detail(accepted, owner).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(call(get("/api/account/orders"), owner, false, 200).path("orders").get(0).path("status").asText()).isNotEqualTo("PAID");
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void expiredAmbiguousRecoveryNeverCancelsRedispatchesOrHidesSupportContext() throws Exception {
        Attempt accepted = seed(owner);
        for (String state : List.of("UNKNOWN", "CREATING", "READY")) {
            jdbc.update("UPDATE web_checkout_attempts SET state=?,expires_at=?,updated_at=now() WHERE id=?", state,
                    Timestamp.from(Instant.now().minusSeconds(1)), accepted.id());
            var recovered = call(get("/api/account/checkout"), owner, false, 200).path("checkout");
            assertThat(recovered.path("id").asText()).isEqualTo(accepted.id());
            assertThat(recovered.path("status").asText()).isEqualTo("UNKNOWN".equals(state) ? "UNKNOWN" : "CREATING");
            assertThat(recovered.path("paymentUrl").isNull()).isTrue();
        }
        jdbc.update("UPDATE web_checkout_attempts SET updated_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(121)), accepted.id());
        assertThat(detail(accepted, owner).path("status").asText()).isEqualTo("UNKNOWN");
        jdbc.update("UPDATE web_checkout_attempts SET state='PAYABLE' WHERE id=?", accepted.id());
        assertThat(detail(accepted, owner).path("status").asText()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, accepted.orderId())).isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isEqualTo(1);
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void signedVerifiedCaptureRecoversPaidWithExactSnapshotAndNoUrlOrProviderCall() throws Exception {
        Attempt accepted = seed(owner);
        var before = detail(accepted, owner);
        admin.confirmGatewayPayment(accepted.orderId(), "pay_account_verified", "plink_" + accepted.id(), new BigDecimal("200"),
                accepted.id(), "web_" + accepted.id(), "INR");
        // Real settlement verification is exercised; notification boundary remains mocked.
        var paid = detail(accepted, owner);
        assertThat(paid.path("status").asText()).isEqualTo("PAID");
        assertThat(paid.path("orderStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(paid.path("paymentUrl").isNull()).isTrue();
        assertThat(paid.path("quote")).isEqualTo(before.path("quote"));
        jdbc.update("UPDATE web_checkout_attempts SET expires_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(1)), accepted.id());
        assertThat(call(get("/api/account/checkout"), session(owner.customerId()), false, 200).path("checkout").path("status").asText()).isEqualTo("PAID");
        assertThat(call(get("/api/account/orders"), owner, false, 200).path("orders").get(0).path("status").asText()).isEqualTo("PAID");
        jdbc.update("UPDATE payments SET amount=201 WHERE order_id=?", accepted.orderId());
        assertThat(detail(accepted, owner).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(detail(accepted, owner).path("quote").path("totalAmount").decimalValue()).isEqualByComparingTo("200");
        verifyNoInteractions(razorpay);
    }

    @Test void cancelledLateMoneyRemainsReviewableAndLatestMeansLatestNotLatestUnpaid() throws Exception {
        Attempt older = seed(owner);
        Attempt latest = seed(owner);
        jdbc.update("UPDATE web_checkout_attempts SET created_at=? WHERE id=?", Timestamp.from(Instant.now().minusSeconds(3600)), older.id());
        jdbc.update("UPDATE orders SET status='CANCELLED',cutoff_cancelled=true WHERE id=?", latest.orderId());
        jdbc.update("UPDATE payments SET status='REVIEW_REQUIRED' WHERE order_id=?", latest.orderId());
        var recovered = call(get("/api/account/checkout"), owner, false, 200).path("checkout");
        assertThat(recovered.path("id").asText()).isEqualTo(latest.id());
        assertThat(recovered.path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        assertThat(recovered.path("orderStatus").asText()).isEqualTo("CANCELLED");
        assertThat(recovered.path("paymentUrl").isNull()).isTrue();
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void realCheckoutNeverImplicitlyCreatesOrEditsSavedDefaults() throws Exception {
        when(razorpay.isGatewayMode()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.webhookConfigured()).thenReturn(true);
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RazorpayService.PaymentLink("plink_account_checkout", "https://rzp.io/i/account"));
        var saved = save(profile(0), owner, 200);
        var cart = mapper.createObjectNode().put("version", 0).put("date", date.toString());
        cart.putArray("lines").addObject().put("itemId", itemId).put("quantity", 1);
        call(put("/api/cart").contentType("application/json").content(cart.toString()), owner, true, 200);
        var prepared = call(post("/api/checkout/prepare").contentType("application/json").content("{\"cartVersion\":1}"), owner, true, 200);
        var input = mapper.createObjectNode().put("idempotencyKey", UUID.randomUUID().toString()).put("cartVersion", 1)
                .put("quoteToken", prepared.path("quoteToken").asText()).put("name", "Checkout different name")
                .put("address", "Different checkout address, Gurugram").put("deliveryPreference", "DOOR").put("loafPreference", "SLICED");
        var accepted = call(post("/api/checkout").contentType("application/json").content(input.toString()), owner, true, 200);
        assertThat(call(get("/api/account/profile"), owner, false, 200)).isEqualTo(saved);
        var recovered = call(get("/api/account/checkout"), owner, false, 200).path("checkout");
        assertThat(recovered.path("id")).isEqualTo(accepted.path("id"));
        assertThat(recovered.path("fulfillment").path("name").asText()).isEqualTo("Checkout different name");
        assertThat(recovered.path("quote").path("lines").equals((left, right) ->
            left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue())
                : left.equals(right) ? 0 : 1, prepared.path("quote").path("lines"))).isTrue();
        for (int i = 0; i < 3; i++) call(get("/api/account/checkout"), owner, false, 200);
        verify(razorpay).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
        verify(razorpay, never()).cancelPaymentLink(any());
    }

    @Test void deletingCustomerCascadesWebsiteDefaults() throws Exception {
        save(profile(0), owner, 200);
        jdbc.update("DELETE FROM customers WHERE id=?", owner.customerId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
    }

        private HttpResponse<String> wire(String method, String path, JsonNode body) throws Exception {
        var cookies = java.util.Arrays.stream(owner.cookies()).map(c -> c.getName() + "=" + c.getValue())
            .collect(java.util.stream.Collectors.joining("; "));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(25)).header("Cookie", cookies).header("X-XSRF-TOKEN", owner.csrf());
        if (body != null) request.header("Content-Type", "application/json");
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        return response;
        }

        private JsonNode wireJson(String method, String path, JsonNode body) throws Exception {
        var response = wire(method, path, body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
        }

        @Test void loopbackRealCheckoutPaidHistoryAndBrokenHistoryExportFrontendContract() throws Exception {
        when(razorpay.isGatewayMode()).thenReturn(true);
        when(razorpay.isConfigured()).thenReturn(true);
        when(razorpay.webhookConfigured()).thenReturn(true);
        when(razorpay.createPaymentLink(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new RazorpayService.PaymentLink("plink_account_wire", "https://rzp.io/i/account-wire"));
        jdbc.update("UPDATE customers SET name='Wire Customer',delivery_address=? WHERE id=?", ADDRESS, owner.customerId());
        var payload = mapper.createObjectNode();
        payload.set("session", wireJson("GET", "/api/session", null));
        payload.set("profile", wireJson("GET", "/api/account/profile", null));
        var cart = mapper.createObjectNode().put("version", 0).put("date", date.toString());
        cart.putArray("lines").addObject().put("itemId", itemId).put("quantity", 1);
        wireJson("PUT", "/api/cart", cart);
        var prepared = wireJson("POST", "/api/checkout/prepare", mapper.createObjectNode().put("cartVersion", 1));
        var input = mapper.createObjectNode().put("idempotencyKey", UUID.randomUUID().toString()).put("cartVersion", 1)
            .put("quoteToken", prepared.path("quoteToken").asText()).put("name", "Wire Customer")
            .put("address", ADDRESS).put("deliveryPreference", "DOOR").put("loafPreference", "SLICED");
        var accepted = wireJson("POST", "/api/checkout", input);
        String id = accepted.path("id").asText();
        long orderId = jdbc.queryForObject("SELECT order_id FROM web_checkout_attempts WHERE id=?", Long.class, id);
        payload.set("payable", wireJson("GET", "/api/account/orders/" + id, null));
        admin.confirmGatewayPayment(orderId, "pay_account_wire", "plink_account_wire", prepared.path("quote").path("totalAmount").decimalValue(),
            id, "web_" + id, "INR");
        payload.set("paid", wireJson("GET", "/api/account/orders/" + id, null));
        subscription(owner, "COMPLETED", "2026-08-01 09:00:00", LocalDate.of(2026, 8, 7));
        payload.set("history", wireJson("GET", "/api/account/orders?page=0", null));
        payload.set("profile", wireJson("GET", "/api/account/profile", null));
        payload.set("location", wireJson("GET", "/api/account/location", null));
        assertThat(payload.path("paid").path("status").asText()).isEqualTo("PAID");
        assertThat(payload.path("history").path("orders").get(0).path("createdAt").isTextual()).isTrue();
        assertThat(Instant.parse(payload.path("paid").path("createdAt").asText())).isNotNull();
        // A corrupt historical snapshot is independent of valid identity/profile. Local synthetic data only.
        jdbc.update("UPDATE web_checkout_attempts SET snapshot=jsonb_set(snapshot,'{quote}','null'::jsonb) WHERE id=?", id);
        var failed = wire("GET", "/api/account/orders?page=0", null);
        assertThat(failed.statusCode()).isEqualTo(500);
        payload.put("historyFailureStatus", failed.statusCode());
        assertThat(wireJson("GET", "/api/account/profile", null)).isEqualTo(payload.get("profile"));
        // Test-only wire artifact: no real customer, OTP or provider data. Consumed by sibling Node contract tests.
        Files.writeString(Path.of("target/account-http-contract.json"), mapper.writeValueAsString(payload));
        verify(razorpay).createPaymentLink(any(), any(), any(), any(), any(), any(), any());
        }

    @Test void existingPreferencesAndOwnPinAreReadOnlySuggestionsAndSavedProfileWins() throws Exception {
        Attempt previous = seed(owner);
        jdbc.update("UPDATE orders SET delivery_preference='IN_PERSON',loaf_preference='SLICED' WHERE id=?", previous.orderId());
        Attempt foreign = seed(browser());
        jdbc.update("UPDATE orders SET delivery_preference='GATE',loaf_preference='WHOLE' WHERE id=?", foreign.orderId());
        Attempt draft = seed(owner);
        jdbc.update("UPDATE orders SET status='DRAFT',delivery_preference='DOOR',loaf_preference='WHOLE' WHERE id=?", draft.orderId());
        jdbc.update("UPDATE customers SET location_lat=28.45,location_lng=77.02 WHERE id=?", owner.customerId());
        var shared = jdbc.queryForMap("SELECT * FROM customers WHERE id=?", owner.customerId());
        shared.remove("last_web_activity_at"); // Normal authentication activity is intentionally updated.
        var order = jdbc.queryForMap("SELECT * FROM orders WHERE id=?", previous.orderId());
        var suggestions = call(get("/api/account/profile"), owner, false, 200);
        fields(suggestions, "version", "name", "address", "deliveryPreference", "loafPreference");
        assertThat(suggestions.path("deliveryPreference").asText()).isEqualTo("IN_PERSON");
        assertThat(suggestions.path("loafPreference").asText()).isEqualTo("SLICED");
        var location = call(get("/api/account/location"), owner, false, 200);
        fields(location, "latitude", "longitude");
        assertThat(location.path("latitude").decimalValue()).isEqualByComparingTo("28.45");
        assertThat(location.path("longitude").decimalValue()).isEqualByComparingTo("77.02");
        var other = call(get("/api/account/location"), browser(), false, 200);
        assertThat(other.path("latitude").isNull()).isTrue();
        assertThat(other.path("longitude").isNull()).isTrue();
        assertThat(call(get("/api/account/location").param("customerId", String.valueOf(foreign.orderId())), owner, false, 200))
                .isEqualTo(location);
        call(get("/api/account/location").header("Origin", "https://evil.example"), owner, false, 403);
        call(put("/api/account/location").contentType("application/json").content("{}"), owner, true, 405);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_customer_profiles WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        var saved = save(profile(0), owner, 200);
        assertThat(call(get("/api/account/profile"), owner, false, 200)).isEqualTo(saved);
        assertThat(call(get("/api/account/location"), owner, false, 200)).isEqualTo(location);
        var after = jdbc.queryForMap("SELECT * FROM customers WHERE id=?", owner.customerId());
        after.remove("last_web_activity_at");
        assertThat(after).isEqualTo(shared);
        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id=?", previous.orderId())).isEqualTo(order);
        verifyNoInteractions(razorpay, notifier);
    }

    @Test void incompleteAndOutOfRangePinsAreOmittedWithoutChangingStoredValues() throws Exception {
        for (String[] pair : new String[][] {{null, null}, {"28", null}, {null, "77"}, {"90.000001", "77"},
                {"28", "180.000001"}, {"-90.000001", "77"}, {"28", "-180.000001"}}) {
            jdbc.update("UPDATE customers SET location_lat=?,location_lng=? WHERE id=?",
                    pair[0] == null ? null : new BigDecimal(pair[0]), pair[1] == null ? null : new BigDecimal(pair[1]), owner.customerId());
            var before = jdbc.queryForMap("SELECT location_lat,location_lng FROM customers WHERE id=?", owner.customerId());
            var result = call(get("/api/account/location"), owner, false, 200);
            fields(result, "latitude", "longitude");
            assertThat(result.path("latitude").isNull()).isTrue();
            assertThat(result.path("longitude").isNull()).isTrue();
            assertThat(jdbc.queryForMap("SELECT location_lat,location_lng FROM customers WHERE id=?", owner.customerId())).isEqualTo(before);
        }
        for (String[] pair : new String[][] {{"0", "0"}, {"-90", "180"}, {"90", "-180"}}) {
            jdbc.update("UPDATE customers SET location_lat=?,location_lng=? WHERE id=?", new BigDecimal(pair[0]), new BigDecimal(pair[1]), owner.customerId());
            var result = call(get("/api/account/location"), owner, false, 200);
            assertThat(result.path("latitude").decimalValue()).isEqualByComparingTo(pair[0]);
            assertThat(result.path("longitude").decimalValue()).isEqualByComparingTo(pair[1]);
        }
        verifyNoInteractions(razorpay, notifier);
    }
}