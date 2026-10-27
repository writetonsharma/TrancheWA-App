package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.payment.RazorpayService;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** All four new routes traverse real loopback HTTP, security, services and local PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.forward-headers-strategy=none",
        "bakery.web.enabled=true", "bakery.web.checkout.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.cookie.secure=false", "bakery.cleanup.enabled=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bakery.web.allowed-origins=https://tranchebakery.in"
})
@ActiveProfiles("test")
class WebCartHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthCrypto crypto;
    @Autowired CustomerRepository customers;
    @MockitoBean WhatsAppClient whatsapp;
    @MockitoBean RazorpayService razorpay;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Long> customerIds = new ArrayList<>();
    Long categoryId;
    Long itemId;
    Browser owner;
    LocalDate date;

    record Browser(long customerId, String cookies, String csrf) {}

    @BeforeEach void setup() {
        categoryId = jdbc.queryForObject("INSERT INTO menu_categories(name, display_order, active) VALUES (?,0,true) RETURNING id",
                Long.class, "Web-cart-" + UUID.randomUUID());
        itemId = jdbc.queryForObject("INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,'Test loaf',200,0,true) RETURNING id",
                Long.class, categoryId);
        owner = browser();
        date = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(7)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    @AfterEach void cleanup() {
        for (Long id : customerIds) jdbc.update("DELETE FROM customers WHERE id = ?", id);
        jdbc.update("DELETE FROM menu_items WHERE category_id = ?", categoryId);
        jdbc.update("DELETE FROM menu_categories WHERE id = ?", categoryId);
        verifyNoInteractions(whatsapp, razorpay);
    }

    Browser browser() {
        Customer customer = new Customer();
        customer.setPhone("9197" + String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
        customer.setMarketingOptIn(false);
        customer = customers.saveAndFlush(customer);
        customerIds.add(customer.getId());
        return session(customer.getId());
    }

    Browser session(long id) {
        String binding = crypto.token();
        String session = crypto.token();
        String csrf = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO web_customer_sessions(token_hash,customer_id,browser_hash,created_at,last_seen_at,expires_at) VALUES (?,?,?,?,?,?)",
                crypto.hash("session", session), id, crypto.hash("browser", binding), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)));
        return new Browser(id, AuthCookies.BROWSER + "=" + binding + "; " + AuthCookies.SESSION + "=" + session
                + "; TRANCHE_WEB_CSRF=" + csrf, csrf);
    }

    HttpResponse<String> call(String method, String path, String body, Browser browser, boolean csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
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

    String input(long version, int quantity) {
        return "{\"version\":" + version + ",\"lines\":[{\"itemId\":" + itemId
                + ",\"quantity\":" + quantity + "}],\"date\":\"" + date + "\"}";
    }

    JsonNode json(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return mapper.readTree(response.body());
    }

    @Test void durableOwnedCartPersistsAcrossSessionsAndDoesNotCreateOrders() throws Exception {
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200).path("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_carts WHERE customer_id = ?", Integer.class, owner.customerId())).isZero();
        var saved = json(call("PUT", "/api/cart", input(0, 2), owner, true), 200);
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("lines").get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(saved.path("quote").path("listSubtotal").decimalValue()).isEqualByComparingTo("400");
        var loaded = json(call("GET", "/api/cart", null, session(owner.customerId()), false), 200);
        assertThat(loaded).isEqualTo(saved);
        assertThat(loaded.has("customerId")).isFalse();
        assertThat(loaded.has("orderId")).isFalse();
        assertThat(loaded.toString()).doesNotContain("phone", "gateway", "capacity");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = ?", Integer.class, owner.customerId())).isZero();
        assertThat(json(call("GET", "/api/cart", null, browser(), false), 200).path("lines")).isEmpty();
    }

    @Test void competingFirstSavesAndLaterSavesHaveExactlyOneWinner() throws Exception {
        race(0);
        race(1);
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200).path("version").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_carts WHERE customer_id = ?", Integer.class, owner.customerId())).isEqualTo(1);
    }

    void race(long version) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> first = () -> call("PUT", "/api/cart", input(version, 1), owner, true).statusCode();
            Callable<Integer> second = () -> call("PUT", "/api/cart", input(version, 2), owner, true).statusCode();
            var results = executor.invokeAll(List.of(first, second), 25, TimeUnit.SECONDS);
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); }
    }

    @Test void strictInputsNeverOverwriteSavedCartAndOldVersionsConflict() throws Exception {
        json(call("PUT", "/api/cart", input(0, 1), owner, true), 200);
        json(call("PUT", "/api/cart", input(0, 2), owner, true), 409);
        String valid = input(1, 1);
        List<String> invalid = List.of(
                valid.replace("\"version\":1", "\"version\":\"1\""),
                valid.replace("\"version\":1", "\"version\":-1"),
                valid.replace("\"version\":1", "\"version\":1.0"),
                valid.replace("\"version\":1", "\"version\":9007199254740991"),
                valid.replace("\"version\":1,", ""),
                valid.replace("\"quantity\":1", "\"quantity\":1.5"),
                valid.replace("\"quantity\":1", "\"quantity\":\"1\""),
                valid.replace("\"quantity\":1", "\"quantity\":100"),
                valid.replace("\"quantity\":1", "\"quantity\":0"),
                valid.replace("\"itemId\":" + itemId, "\"itemId\":9223372036854775808"),
                valid.replace("\"itemId\":" + itemId, "\"itemId\":999999999"),
                valid.replace(date.toString(), "9999-12-31"),
                valid.replace(date.toString(), "2026-02-30"),
                valid.replace(date.toString(), LocalDate.now().minusDays(1).toString()),
                valid.replace("{\"version", "{\"customerId\":123,\"version"),
                valid.replace("\"quantity\":1", "\"quantity\":1,\"price\":1"),
                "{\"version\":1,\"lines\":[" + ("{\"itemId\":" + itemId + ",\"quantity\":1},").repeat(40)
                        + "{\"itemId\":" + itemId + ",\"quantity\":1}]}");
        for (String body : invalid) json(call("PUT", "/api/cart", body, owner, true), 400);
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200).path("version").asLong()).isEqualTo(1);
    }

    @Test void duplicatesMergeAndMissingDateAndCapacityRemainAdvisory() throws Exception {
        String duplicate = "{\"version\":0,\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1},{\"itemId\":" + itemId + ",\"quantity\":2}]}";
        var saved = json(call("PUT", "/api/cart", duplicate, owner, true), 200);
        assertThat(saved.path("lines")).hasSize(1);
        assertThat(saved.path("lines").get(0).path("quantity").asInt()).isEqualTo(3);
        assertThat(saved.path("quote").path("valid").asBoolean()).isFalse();
        assertThat(saved.path("problems").get(0).path("code").asText()).isEqualTo("DATE_REQUIRED");
        // Historical over-capacity baskets remain readable, not silently truncated.
        jdbc.update("UPDATE web_carts SET lines=?::jsonb,delivery_date=? WHERE customer_id=?",
            "[{\"itemId\":" + itemId + ",\"quantity\":99}]", date, owner.customerId());
        var full = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(full.path("problems").get(0).path("code").asText()).isEqualTo("FULLY_BOOKED");
        json(call("PUT", "/api/cart", duplicate.replace("\"version\":0", "\"version\":1")
                .replace("\"quantity\":1", "\"quantity\":99"), owner, true), 400);
        assertThat(json(call("PUT", "/api/cart", "{\"version\":1,\"lines\":[],\"date\":null}", owner, true), 200)
                .path("lines")).isEmpty();
    }

    @Test void fiveItemsIncludingDuplicateIdsSaveButSixNeverInsertOrOverwriteCart() throws Exception {
        Long secondId = jdbc.queryForObject("INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,'Second test loaf',200,1,true) RETURNING id",
            Long.class, categoryId);
        String mergedSix = input(0, 3).replace("\"quantity\":3}",
            "\"quantity\":3},{\"itemId\":" + itemId + ",\"quantity\":3}");
        var independentLines = mapper.createArrayNode();
        independentLines.addObject().put("itemId", itemId).put("quantity", 1);
        independentLines.addObject().put("itemId", secondId).put("quantity", 1);
        for (int i = 2; i < 6; i++) {
            Long id = jdbc.queryForObject("INSERT INTO menu_items(category_id,name,price,display_order,active) VALUES (?,?,200,?,true) RETURNING id",
                Long.class, categoryId, "Independent test loaf " + i, i);
            independentLines.addObject().put("itemId", id).put("quantity", 1);
        }
        String independentSix = "{\"version\":0,\"lines\":" + independentLines + ",\"date\":\"" + date + "\"}";
        for (String body : List.of(input(0, 6), mergedSix, independentSix)) {
            var rejected = json(call("PUT", "/api/cart", body, owner, true), 400);
            assertThat(rejected.path("problems").get(0).path("code").asText()).isEqualTo("RETAIL_LIMIT_EXCEEDED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM web_carts WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        }
        String five = mergedSix.replace("\"quantity\":3", "\"quantity\":2")
            .replace("}],\"date\"", "},{\"itemId\":" + secondId + ",\"quantity\":1}],\"date\"");
        var saved = json(call("PUT", "/api/cart", five, owner, true), 200);
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("lines")).hasSize(2);
        assertThat(saved.path("lines").get(0).path("quantity").asInt()).isEqualTo(4);
        assertThat(saved.path("quote").path("itemCount").asInt()).isEqualTo(5);
        // This class deliberately uses daily-capacity=3: saves remain advisory,
        // independently of the website's five-item acceptance ceiling.
        assertThat(saved.path("problems").get(0).path("code").asText()).isEqualTo("FULLY_BOOKED");
        var stored = jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId());
        for (String body : List.of(input(0, 6), mergedSix, independentSix)) {
            var rejected = json(call("PUT", "/api/cart", body.replace("\"version\":0", "\"version\":1"), owner, true), 400);
            assertThat(rejected.path("problems").get(0).path("code").asText()).isEqualTo("RETAIL_LIMIT_EXCEEDED");
            assertThat(jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId())).isEqualTo(stored);
        }
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_quotes WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_checkout_attempts WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
    }

    @Test void legacyOversizedCartRemainsReadableAndCanOnlyBeSavedAtFiveOrBelow() throws Exception {
        json(call("PUT", "/api/cart", input(0, 1), owner, true), 200);
        jdbc.update("UPDATE web_carts SET lines=?::jsonb WHERE customer_id=?",
            "[{\"itemId\":" + itemId + ",\"quantity\":8}]", owner.customerId());
        var stored = jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId());
        var legacy = json(call("GET", "/api/cart", null, session(owner.customerId()), false), 200);
        assertThat(legacy.path("version").asLong()).isEqualTo(1);
        assertThat(legacy.path("lines").get(0).path("quantity").asInt()).isEqualTo(8);
        assertThat(legacy.path("quote").path("itemCount").asInt()).isEqualTo(8);
        for (int quantity : List.of(8, 7, 6)) {
            var rejected = json(call("PUT", "/api/cart", input(1, quantity), owner, true), 400);
            assertThat(rejected.path("problems").get(0).path("code").asText()).isEqualTo("RETAIL_LIMIT_EXCEEDED");
            assertThat(jdbc.queryForMap("SELECT * FROM web_carts WHERE customer_id=?", owner.customerId())).isEqualTo(stored);
        }
        var reduced = json(call("PUT", "/api/cart", input(1, 5), owner, true), 200);
        assertThat(reduced.path("version").asLong()).isEqualTo(2);
        assertThat(reduced.path("quote").path("itemCount").asInt()).isEqualTo(5);
        assertThat(reduced.path("problems").get(0).path("code").asText()).isEqualTo("FULLY_BOOKED");
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200)).isEqualTo(reduced);
        assertThat(json(call("PUT", "/api/cart", input(2, 4), owner, true), 200)
            .path("quote").path("itemCount").asInt()).isEqualTo(4);
    }

    @Test void stalePricesRefreshAndRetiredOrAgedBasketsRemainReadableWithoutMutation() throws Exception {
        json(call("PUT", "/api/cart", input(0, 1), owner, true), 200);
        jdbc.update("UPDATE menu_items SET price=300 WHERE id=?", itemId);
        var repriced = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(repriced.path("quote").path("listSubtotal").decimalValue()).isEqualByComparingTo("300");
        assertThat(repriced.path("version").asLong()).isEqualTo(1);
        jdbc.update("UPDATE menu_items SET active=false WHERE id=?", itemId);
        var retired = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(retired.path("quote").isNull()).isTrue();
        assertThat(retired.path("problems").get(0).path("code").asText()).isEqualTo("ITEM_UNAVAILABLE");
        json(call("PUT", "/api/cart", input(1, 1), owner, true), 400);
        jdbc.update("UPDATE menu_items SET active=true WHERE id=?", itemId);
        jdbc.update("UPDATE web_carts SET delivery_date=? WHERE customer_id=?", LocalDate.now().minusDays(1), owner.customerId());
        var aged = json(call("GET", "/api/cart", null, owner, false), 200);
        assertThat(aged.path("quote").isNull()).isTrue();
        assertThat(aged.path("problems").get(0).path("code").asText()).isEqualTo("INVALID_DATE");
        assertThat(aged.path("version").asLong()).isEqualTo(1);
    }

    @Test void friendsFamilyAndCreditsAreQuotedNotSilentlyDisabledOrConsumed() throws Exception {
        jdbc.update("UPDATE customers SET pricing_override=100, free_delivery=true, credit_balance=70 WHERE id=?", owner.customerId());
        var saved = json(call("PUT", "/api/cart", input(0, 1), owner, true), 200);
        assertThat(saved.path("quote").path("creditApplied").decimalValue()).isEqualByComparingTo("70");
        assertThat(saved.path("quote").path("totalAmount").decimalValue()).isEqualByComparingTo("30");
        json(call("POST", "/api/checkout", "{}", owner, true), 400);
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM customers WHERE id=?", BigDecimal.class, owner.customerId()))
                .isEqualByComparingTo("70");
        jdbc.update("UPDATE customers SET credit_balance=1000 WHERE id=?", owner.customerId());
        assertThat(json(call("GET", "/api/cart", null, owner, false), 200).path("quote").path("totalAmount").decimalValue()).isZero();
        json(call("POST", "/api/checkout", "{}", owner, true), 400);
    }

    @Test void allRoutesRequireCookiesAndUnsafeRoutesRequireCsrfAndInvalidCheckoutNeverMutates() throws Exception {
        for (String path : List.of("/api/cart", "/api/checkout/" + UUID.randomUUID()))
            json(call("GET", path, null, null, false), 401);
        json(call("PUT", "/api/cart", input(0, 1), null, true), 403);
        json(call("POST", "/api/checkout", "{}", null, true), 403);
        Browser csrfOnly = new Browser(0, "TRANCHE_WEB_CSRF=" + owner.csrf(), owner.csrf());
        json(call("PUT", "/api/cart", input(0, 1), csrfOnly, true), 401);
        json(call("POST", "/api/checkout", "{}", csrfOnly, true), 401);
        json(call("PUT", "/api/cart", input(0, 1), owner, false), 403);
        json(call("POST", "/api/checkout", "{}", owner, false), 403);
        for (int i = 0; i < 2; i++) {
            var blocked = json(call("POST", "/api/checkout", "{\"idempotencyKey\":\"same-key\",\"amount\":1}", owner, true), 400);
            assertThat(blocked.path("problems").get(0).path("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        json(call("GET", "/api/checkout/" + UUID.randomUUID(), null, owner, false), 404);
        json(call("GET", "/api/checkout/" + UUID.randomUUID(), null, browser(), false), 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class, owner.customerId())).isZero();
        jdbc.update("UPDATE web_customer_sessions SET expires_at=? WHERE customer_id=?", Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        json(call("GET", "/api/cart", null, owner, false), 401);
        json(call("POST", "/api/checkout", "{}", owner, true), 401);
    }

    @Test void claimedCustomerAdminAndCrossOriginCannotAccessCart() throws Exception {
        var base = "http://127.0.0.1:" + port;
        var admin = http.send(HttpRequest.newBuilder(URI.create(base + "/api/cart"))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("admin:testpass".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("X-Customer-Id", String.valueOf(owner.customerId())).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(admin.statusCode()).isEqualTo(401);
        var evil = http.send(HttpRequest.newBuilder(URI.create(base + "/api/cart"))
                .header("Cookie", owner.cookies()).header("Origin", "https://evil.example").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(evil.statusCode()).isEqualTo(403);
    }

    JsonNode shoppingItem(JsonNode shopping) {
        for (JsonNode item : shopping.path("items")) {
            if (item.path("id").asLong() == itemId.longValue()) return item;
        }
        throw new AssertionError("Shopping response omitted the active test item");
    }

    @Test void shoppingAndQuotePersonalizeOnlyWithVerifiedBoundSessionAndNeverConsumeCredit() throws Exception {
        jdbc.update("UPDATE customers SET pricing_override=100, free_delivery=true, credit_balance=70 WHERE id=?",
            owner.customerId());
        Browser anonymous = new Browser(0, "TRANCHE_WEB_CSRF=" + owner.csrf(), owner.csrf());
        String quoteBody = "{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1}]}";
        JsonNode publicItem = shoppingItem(json(call("POST", "/api/shopping", "{}", anonymous, true), 200));
        JsonNode privateItem = shoppingItem(json(call("POST", "/api/shopping", "{}", owner, true), 200));
        assertThat(privateItem.path("listPrice").decimalValue()).isEqualByComparingTo("200");
        assertThat(privateItem.path("unitPrice").decimalValue()).isEqualByComparingTo("100");
        JsonNode publicQuote = json(call("POST", "/api/quote", quoteBody, anonymous, true), 200);
        JsonNode privateQuote = json(call("POST", "/api/quote", quoteBody, owner, true), 200);
        assertThat(publicQuote.path("creditApplied").decimalValue()).isZero();
        assertThat(privateQuote.path("creditApplied").decimalValue()).isEqualByComparingTo("70");
        assertThat(privateQuote.path("totalAmount").decimalValue()).isEqualByComparingTo("30");

        List<Browser> invalid = List.of(
            new Browser(owner.customerId(), anonymous.cookies() + "; " + AuthCookies.BROWSER
                + "=invalid; " + AuthCookies.SESSION + "=invalid", owner.csrf()),
            new Browser(owner.customerId(), anonymous.cookies() + "; " + AuthCookies.BROWSER
                + "=" + crypto.token() + "; " + AuthCookies.SESSION + "=" + crypto.token(), owner.csrf()),
            new Browser(owner.customerId(), owner.cookies().replaceFirst(
                AuthCookies.BROWSER + "=[^;]+", AuthCookies.BROWSER + "=" + crypto.token()), owner.csrf()));
        for (Browser browser : invalid) {
            assertThat(shoppingItem(json(call("POST", "/api/shopping", "{}", browser, true), 200)))
                .isEqualTo(publicItem);
            assertThat(json(call("POST", "/api/quote", quoteBody, browser, true), 200)).isEqualTo(publicQuote);
        }
        // Even administrator credentials and claimed customer IDs cannot rescue a forged cookie pair.
        for (String path : List.of("/api/shopping", "/api/quote")) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Cookie", invalid.get(0).cookies()).header("X-XSRF-TOKEN", owner.csrf())
                .header("X-Customer-Id", String.valueOf(owner.customerId()))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "admin:testpass".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(path.endsWith("shopping") ? "{}" : quoteBody))
                .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
            assertThat(response.headers().firstValue("Pragma")).hasValue("no-cache");
            JsonNode result = json(response, 200);
            assertThat(path.endsWith("shopping") ? shoppingItem(result) : result)
                .isEqualTo(path.endsWith("shopping") ? publicItem : publicQuote);
        }
        jdbc.update("UPDATE web_customer_sessions SET expires_at=? WHERE customer_id=?",
            Timestamp.from(Instant.now().minusSeconds(1)), owner.customerId());
        assertThat(shoppingItem(json(call("POST", "/api/shopping", "{}", owner, true), 200)))
            .isEqualTo(publicItem);
        assertThat(json(call("POST", "/api/quote", quoteBody, owner, true), 200)).isEqualTo(publicQuote);
        assertThat(jdbc.queryForObject("SELECT credit_balance FROM customers WHERE id=?", BigDecimal.class,
            owner.customerId())).isEqualByComparingTo("70");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_carts WHERE customer_id=?", Integer.class,
            owner.customerId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?", Integer.class,
            owner.customerId())).isZero();
    }
}