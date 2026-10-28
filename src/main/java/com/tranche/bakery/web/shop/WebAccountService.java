package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.tranche.bakery.order.CapacityLock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit projections only: no shared-customer writes and no provider calls. */
@Service
public class WebAccountService {
    private static final Set<String> PROFILE_FIELDS = Set.of("version", "name", "address", "deliveryPreference", "loafPreference");
    private static final String OWNED = """
            FROM web_checkout_attempts a JOIN orders o ON o.id=a.order_id
            JOIN payments p ON p.order_id=o.id
            WHERE a.customer_id=? AND o.customer_id=a.customer_id AND o.channel='WEB'
            AND o.source='RETAIL' AND o.subscription_id IS NULL AND o.status<>'DRAFT'
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebCartService carts;
    private final WebCheckoutService checkouts;
    private final CapacityLock capacity;
    private final TransactionTemplate tx;

    public WebAccountService(JdbcTemplate jdbc, ObjectMapper mapper, WebCartService carts,
            WebCheckoutService checkouts, CapacityLock capacity, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.mapper = mapper; this.carts = carts; this.checkouts = checkouts;
        this.capacity = capacity; this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
    }

    public record Profile(long version, String name, String address, String deliveryPreference, String loafPreference) { }
    public record Location(BigDecimal latitude, BigDecimal longitude) { }
    private record Preferences(String delivery, String loaf) { }
    public record Fulfillment(String name, String address, String deliveryPreference, String loafPreference) { }
    public record Summary(String id, String orderNumber, Instant createdAt, LocalDate deliveryDate,
                          String orderStatus, String status, BigDecimal totalAmount) { }
    public record History(List<Summary> orders, int page, boolean hasMore) { }
    public record Detail(String id, String orderNumber, Instant createdAt, String orderStatus, String status,
                         String paymentUrl, Instant expiresAt, Fulfillment fulfillment, StorefrontDtos.Quote quote) { }
    public record Recovery(Detail checkout) { }
    private record Stored(String id, String number, Instant created, String orderStatus, JsonNode snapshot) { }

    public Profile profile(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            var customer = carts.requireCustomer(request);
            var rows = jdbc.query("""
                    SELECT version,name,address,delivery_preference,loaf_preference
                    FROM web_customer_profiles WHERE customer_id=?
                    """, (rs, row) -> new Profile(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                    customer.getId());
            if (!rows.isEmpty()) return rows.get(0);
            // WhatsApp keeps preferences on previous orders, not Customer. Read only
            // accepted retail orders; never copy another customer's or a subscription's defaults.
            var preferences = jdbc.query("""
                    SELECT
                    (SELECT delivery_preference FROM orders WHERE customer_id=? AND subscription_id IS NULL
                     AND source='RETAIL' AND status NOT IN ('DRAFT','CANCELLED')
                     AND delivery_preference IN ('GATE','DOOR','IN_PERSON') ORDER BY created_at DESC,id DESC LIMIT 1),
                    (SELECT loaf_preference FROM orders WHERE customer_id=? AND subscription_id IS NULL
                     AND source='RETAIL' AND status NOT IN ('DRAFT','CANCELLED')
                     AND loaf_preference IN ('SLICED','WHOLE') ORDER BY created_at DESC,id DESC LIMIT 1)
                    """, (rs, row) -> new Preferences(rs.getString(1), rs.getString(2)), customer.getId(), customer.getId());
            var previous = preferences.isEmpty() ? new Preferences(null, null) : preferences.get(0);
            // Suggestions are not persisted website defaults or consent.
            return new Profile(0, suggestion(customer.getName(), 1, 100),
                    suggestion(customer.getDeliveryAddress(), 10, 2000),
                    previous.delivery() == null ? "" : previous.delivery(), previous.loaf() == null ? "" : previous.loaf());
        }));
    }

    public Location location(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            var customer = carts.requireCustomer(request);
            if (!request.getParameterMap().isEmpty()) throw invalid();
            BigDecimal lat = customer.getLocationLat(), lng = customer.getLocationLng();
            // Omit both when legacy coordinates are incomplete or outside geographic bounds.
            if (lat == null || lng == null || lat.abs().compareTo(BigDecimal.valueOf(90)) > 0
                    || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) return new Location(null, null);
            return new Location(lat, lng);
        }));
    }

    public Profile save(JsonNode body, HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            var customer = carts.requireCustomer(request); // Serializes both first insert and subsequent CAS.
            Profile input = profileInput(body);
            int changed;
            if (input.version() == 0) {
                changed = jdbc.update("""
                        INSERT INTO web_customer_profiles(customer_id,version,name,address,delivery_preference,loaf_preference)
                        VALUES (?,1,?,?,?,?) ON CONFLICT(customer_id) DO NOTHING
                        """, customer.getId(), input.name(), input.address(), input.deliveryPreference(), input.loafPreference());
            } else {
                changed = jdbc.update("""
                        UPDATE web_customer_profiles SET version=version+1,name=?,address=?,delivery_preference=?,
                        loaf_preference=?,updated_at=now() WHERE customer_id=? AND version=?
                        """, input.name(), input.address(), input.deliveryPreference(), input.loafPreference(), customer.getId(), input.version());
            }
            if (changed != 1) throw new StorefrontProblemException(HttpStatus.CONFLICT, "PROFILE_CONFLICT",
                    "The profile changed. Reload it before saving your edits.");
            return new Profile(input.version() + 1, input.name(), input.address(), input.deliveryPreference(), input.loafPreference());
        }));
    }

    public History orders(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire(); // Never acquire capacity after the customer lock.
            var customer = carts.requireCustomer(request);
            int page = page(request);
            var rows = stored(" ORDER BY a.created_at DESC,a.id DESC LIMIT 11 OFFSET ?", customer.getId(), page * 10);
            var summaries = rows.stream().limit(10).map(row -> {
                var quote = quote(row.snapshot());
                var payment = checkouts.accountStatus(row.id(), request);
                return new Summary(row.id(), row.number(), row.created(), quote.date(), row.orderStatus(), payment.status(), quote.totalAmount());
            }).toList();
            return new History(summaries, page, rows.size() > 10);
        }));
    }

    public Detail order(String id, HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire();
            var customer = carts.requireCustomer(request);
            if (id == null || !id.matches("[A-Za-z0-9_-]{32}")) throw notFound();
            var rows = stored(" AND a.id=? LIMIT 1", customer.getId(), id);
            if (rows.isEmpty()) throw notFound();
            return detail(rows.get(0), request);
        }));
    }

    public Recovery checkout(HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire();
            var customer = carts.requireCustomer(request);
            var rows = stored(" ORDER BY a.created_at DESC,a.id DESC LIMIT 1", customer.getId());
            return new Recovery(rows.isEmpty() ? null : detail(rows.get(0), request));
        }));
    }

    private Detail detail(Stored row, HttpServletRequest request) {
        var payment = checkouts.accountStatus(row.id(), request);
        var snapshot = row.snapshot();
        var fulfillment = new Fulfillment(snapshot.path("name").asText(""), snapshot.path("address").asText(""),
                snapshot.path("deliveryPreference").asText(""), snapshot.path("loafPreference").asText(""));
        return new Detail(row.id(), row.number(), row.created(), row.orderStatus(), payment.status(), payment.paymentUrl(),
                payment.expiresAt(), fulfillment, quote(snapshot));
    }

    private List<Stored> stored(String suffix, Object... args) {
        return jdbc.query("SELECT a.id,o.order_number,a.created_at,o.status,a.snapshot::text " + OWNED + suffix,
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), parse(rs.getString(5))), args);
    }

    private JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored checkout is unavailable."); }
    }

    private StorefrontDtos.Quote quote(JsonNode snapshot) {
        try { return Objects.requireNonNull(mapper.treeToValue(snapshot.get("quote"), StorefrontDtos.Quote.class)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored checkout is unavailable."); }
    }

    private static int page(HttpServletRequest request) {
        // Do not accept Spring's numeric coercion, duplicate params, array aliases or extra query fields.
        var params = request.getParameterMap();
        if (params.isEmpty()) return 0;
        String[] values = params.get("page");
        if (params.size() != 1 || values == null || values.length != 1
                || !values[0].matches("0|[1-9][0-9]{0,4}")) throw invalid();
        int page = Integer.parseInt(values[0]);
        if (page > 10_000) throw invalid();
        return page;
    }

    private static Profile profileInput(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != PROFILE_FIELDS.size()) throw invalid();
        var fields = body.fieldNames();
        while (fields.hasNext()) if (!PROFILE_FIELDS.contains(fields.next())) throw invalid();
        var version = body.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()
                || version.longValue() < 0 || version.longValue() > 9_007_199_254_740_990L) throw invalid();
        String name = bounded(body, "name", 1, 100), address = bounded(body, "address", 10, 2000);
        String delivery = text(body, "deliveryPreference"), loaf = text(body, "loafPreference");
        if (!Set.of("GATE", "DOOR", "IN_PERSON").contains(delivery) || !Set.of("SLICED", "WHOLE").contains(loaf)) throw invalid();
        return new Profile(version.longValue(), name, address, delivery, loaf);
    }

    private static String bounded(JsonNode body, String field, int min, int max) {
        String raw = text(body, field);
        // Includes Unicode line/paragraph separators in addition to ISO control characters.
        if (raw.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) throw invalid();
        String value = raw.strip();
        if (value.length() < min || value.length() > max) throw invalid();
        return value;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private static String suggestion(String raw, int min, int max) {
        if (raw == null) return "";
        // Legacy WhatsApp delivery details can be multiline. Join only layout
        // separators; omit unsafe or out-of-bounds suggestions rather than truncate.
        String value = raw.replaceAll("[\\r\\n\\t\\u2028\\u2029]+", " ").strip();
        if (value.codePoints().anyMatch(Character::isISOControl)
                || value.length() < min || value.length() > max) return "";
        return value;
    }
    private static StorefrontProblemException invalid() {
        return new StorefrontProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Supply valid account details.");
    }
    private static StorefrontProblemException notFound() {
        return new StorefrontProblemException(HttpStatus.NOT_FOUND, "CHECKOUT_NOT_FOUND", "Checkout was not found.");
    }
}