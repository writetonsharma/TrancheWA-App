package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.web.auth.CustomerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable edit-versioned baskets only; quotes never constitute acceptance or reservations. */
@Service
@Transactional
public class WebCartService {
    private static final long MAX_INPUT_VERSION = 9_007_199_254_740_990L;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StorefrontCatalogService catalog;
    private final CustomerSessionService sessions;
    private final Clock clock;

    public WebCartService(JdbcTemplate jdbc, ObjectMapper mapper, StorefrontCatalogService catalog,
            CustomerSessionService sessions, Clock webAuthClock) {
        this.jdbc = jdbc; this.mapper = mapper; this.catalog = catalog;
        this.sessions = sessions; this.clock = webAuthClock;
    }

    public record Line(long itemId, int quantity) {}
    public record Cart(long version, List<Line> lines, LocalDate date,
                       StorefrontDtos.Quote quote, List<StorefrontDtos.Problem> problems) {}
    private record Stored(long version, JsonNode lines, LocalDate date) {}

    public Cart get(HttpServletRequest request) {
        Customer customer = requireCustomer(request);
        var rows = jdbc.query("SELECT version, lines::text, delivery_date FROM web_carts WHERE customer_id = ?",
                (rs, row) -> new Stored(rs.getLong(1), parseStored(rs.getString(2)), rs.getObject(3, LocalDate.class)),
                customer.getId());
        Stored stored = rows.isEmpty() ? new Stored(0, mapper.createArrayNode(), null) : rows.get(0);
        List<Line> lines = readLines(stored.lines());
        try {
            requireBoundedDate(stored.date() == null ? null : mapper.valueToTree(stored.date().toString()));
            var quote = catalog.quote(quoteInput(stored.lines(), stored.date()), customer);
            return new Cart(stored.version(), lines, stored.date(), quote, quote.problems());
        } catch (StorefrontProblemException ex) {
            // Retain stale customer edits, but never present an old amount as currently valid.
            return new Cart(stored.version(), lines, stored.date(), null,
                    List.of(new StorefrontDtos.Problem(ex.code(), ex.getMessage())));
        }
    }

    public Cart put(JsonNode body, HttpServletRequest request) {
        Customer customer = requireCustomer(request);
        if (body == null || !body.isObject()) throw bad("INVALID_REQUEST", "Supply a valid cart request.");
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("version", "lines", "date").contains(fields.next()))
                throw bad("INVALID_REQUEST", "The request contains unsupported fields.");
        }
        JsonNode version = body.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()
                || version.longValue() < 0 || version.longValue() > MAX_INPUT_VERSION)
            throw bad("INVALID_VERSION", "Supply the current non-negative cart version.");
        requireBoundedDate(body.get("date"));
        ObjectNode input = mapper.createObjectNode();
        if (body.has("lines")) input.set("lines", body.get("lines"));
        if (body.has("date")) input.set("date", body.get("date"));
        // Reuse the exact authoritative parser, active-item/day/cutoff/capacity rules and pricing.
        var quote = catalog.quote(input, customer);
        var lines = quote.lines().stream().map(line -> new Line(line.itemId(), line.quantity())).toList();
        long expected = version.longValue();
        String encoded = encode(lines);
        int changed;
        if (expected == 0) {
            changed = jdbc.update("""
                    INSERT INTO web_carts(customer_id, version, lines, delivery_date) VALUES (?,1,?::jsonb,?)
                    ON CONFLICT (customer_id) DO NOTHING
                    """, customer.getId(), encoded, quote.date());
        } else {
            changed = jdbc.update("""
                    UPDATE web_carts SET version = version + 1, lines = ?::jsonb,
                    delivery_date = ?, updated_at = now() WHERE customer_id = ? AND version = ?
                    """, encoded, quote.date(), customer.getId(), expected);
        }
        if (changed != 1) throw new StorefrontProblemException(HttpStatus.CONFLICT, "CART_CONFLICT",
                "The basket changed. Reload it before saving your edits.");
        return new Cart(expected + 1, lines, quote.date(), quote, quote.problems());
    }

    public Customer requireCustomer(HttpServletRequest request) {
        // Joins this transaction: customer/session validity remains protected through the save.
        Customer customer = sessions.currentCustomer(request);
        if (customer == null) throw new StorefrontProblemException(HttpStatus.UNAUTHORIZED,
                "INVALID_SESSION", "Sign in to access your basket.");
        return customer;
    }

    private ObjectNode quoteInput(JsonNode lines, LocalDate date) {
        ObjectNode input = mapper.createObjectNode();
        input.set("lines", lines);
        if (date != null) input.put("date", date.toString());
        return input;
    }

    private void requireBoundedDate(JsonNode date) {
        if (date == null || date.isNull()) return;
        if (!date.isTextual() || !date.textValue().matches("\\d{4}-\\d{2}-\\d{2}"))
            throw bad("INVALID_DATE", "Supply a delivery date in yyyy-MM-dd format.");
        try {
            LocalDate parsed = LocalDate.parse(date.textValue());
            if (parsed.isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))).plusDays(60)))
                throw bad("INVALID_DATE", "Choose a delivery date within the next 60 days.");
        } catch (DateTimeParseException ex) {
            throw bad("INVALID_DATE", "Supply a valid delivery date in yyyy-MM-dd format.");
        }
    }

    private List<Line> readLines(JsonNode lines) {
        return mapper.convertValue(lines, new com.fasterxml.jackson.core.type.TypeReference<List<Line>>() {});
    }

    private JsonNode parseStored(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored basket is unavailable."); }
    }

    private String encode(List<Line> lines) {
        try { return mapper.writeValueAsString(lines); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Basket is unavailable."); }
    }

    private static StorefrontProblemException bad(String code, String message) {
        return new StorefrontProblemException(HttpStatus.BAD_REQUEST, code, message);
    }
}