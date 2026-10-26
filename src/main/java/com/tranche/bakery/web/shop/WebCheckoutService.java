package com.tranche.bakery.web.shop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.order.CapacityLock;
import com.tranche.bakery.order.OrderNumberGenerator;
import com.tranche.bakery.payment.RazorpayService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional acceptance; provider dispatch must never run inside the acceptance transaction. */
@Service
public class WebCheckoutService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> FIELDS = Set.of("idempotencyKey", "cartVersion", "quoteToken",
            "name", "address", "deliveryPreference", "loafPreference");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebCartService carts;
    private final CapacityLock capacity;
    private final RazorpayService razorpay;
    private final OrderNumberGenerator numbers;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final int cutoffHour;

    public WebCheckoutService(JdbcTemplate jdbc, ObjectMapper mapper, WebCartService carts,
            CapacityLock capacity, RazorpayService razorpay, OrderNumberGenerator numbers,
            Clock webAuthClock, PlatformTransactionManager transactionManager,
            @Value("${bakery.order.cutoff-hour:17}") int cutoffHour) {
        this.jdbc = jdbc; this.mapper = mapper; this.carts = carts; this.capacity = capacity;
        this.razorpay = razorpay; this.numbers = numbers; this.clock = webAuthClock;
        this.tx = new TransactionTemplate(Objects.requireNonNull(transactionManager)); this.cutoffHour = cutoffHour;
    }

    public record Prepared(long cartVersion, StorefrontDtos.Quote quote, String quoteToken, Instant expiresAt) { }
    public record Checkout(String id, String status, String paymentUrl, Instant expiresAt) { }
    private record Input(String key, long version, String token, String name, String address,
                         String delivery, String loaf) { }
    private record Acceptance(String id, boolean created) { }
    private record Dispatch(String id, long orderId, String reference, String name, String phone,
                            BigDecimal amount, Instant expires) { }
    private record View(String id, String state, String url, String link, Instant expires,
                        Instant updated, String orderStatus, boolean cutoffCancelled,
                        String paymentStatus, String paymentLink, String paymentId) { }

    public Prepared prepare(JsonNode body, HttpServletRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire(); // Global order: capacity -> customer/session -> attempt/order/payment.
            Customer customer = carts.requireCustomer(request);
            fields(body, Set.of("cartVersion"));
            long version = version(body.get("cartVersion"));
            var cart = carts.get(request);
            if (cart.version() != version) throw conflict("CART_CONFLICT", "Reload the basket before checkout.");
            var quote = validQuote(cart);
            String token = token();
            Instant expires = clock.instant().plusSeconds(600);
            jdbc.update("""
                    INSERT INTO web_checkout_quotes(customer_id,cart_version,token_hash,quote_hash,expires_at)
                    VALUES (?,?,?,?,?) ON CONFLICT(customer_id) DO UPDATE SET cart_version=excluded.cart_version,
                    token_hash=excluded.token_hash,quote_hash=excluded.quote_hash,expires_at=excluded.expires_at
                    """, customer.getId(), version, hash(token), hash(encode(quote)), Timestamp.from(expires));
            return new Prepared(version, quote, token, expires);
        }));
    }

    public Checkout checkout(JsonNode body, HttpServletRequest request) {
        // Parse inside authentication boundary so unauthenticated requests never expose basket validation.
        Acceptance acceptance = Objects.requireNonNull(tx.execute(status -> accept(body, request)));
        // Only the request that committed the new attempt may claim READY. Replays are observation-only.
        if (acceptance.created()) dispatch(acceptance.id());
        return status(acceptance.id(), request);
    }

    private Acceptance accept(JsonNode body, HttpServletRequest request) {
        capacity.acquire();
        Customer customer = carts.requireCustomer(request);
        Input input = input(body);
        String requestHash = hash(encode(input));
        var prior = jdbc.query("SELECT id,request_hash FROM web_checkout_attempts WHERE customer_id=? AND idempotency_key=?",
                (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)), customer.getId(), input.key());
        if (!prior.isEmpty()) {
            if (!prior.get(0).getValue().equals(requestHash))
                throw conflict("IDEMPOTENCY_CONFLICT", "This checkout key was already used for different details.");
            return new Acceptance(prior.get(0).getKey(), false);
        }
        // An uncertain dispatch never permits automatic replacement, even after its deadline.
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM web_checkout_attempts a JOIN orders o ON o.id=a.order_id
                JOIN payments p ON p.order_id=o.id WHERE a.customer_id=? AND
                ((a.state IN ('READY','CREATING','UNKNOWN') AND NOT
                 (p.status='GATEWAY_CAPTURED' AND o.status IN ('CONFIRMED','IN_BAKING','OUT_FOR_DELIVERY','COMPLETED'))) OR
                 (o.status IN ('PENDING_CONFIRMATION','PAYMENT_REVIEW_REQUIRED') AND p.status <> 'GATEWAY_CAPTURED')))
                """, Boolean.class, customer.getId());
        if (Boolean.TRUE.equals(active)) throw conflict("CHECKOUT_IN_PROGRESS", "An earlier checkout needs payment or support review.");
        var cart = carts.get(request);
        if (cart.version() != input.version()) throw conflict("CART_CONFLICT", "Reload the basket before checkout.");
        var quote = validQuote(cart); // Includes active products, item/day/quantity and fresh shared capacity.
        if (positive(customer.getCreditBalance()) || positive(quote.creditApplied()))
            throw conflict("CREDIT_SUPPORT_REQUIRED", "Contact the bakery to use or review account credit.");
        if (quote.totalAmount() == null || quote.totalAmount().signum() <= 0)
            throw conflict("ZERO_PAYABLE_SUPPORT_REQUIRED", "This basket needs support review before ordering.");
        if (!razorpay.isGatewayMode() || !razorpay.isConfigured() || !razorpay.webhookConfigured())
            throw new StorefrontProblemException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_UNAVAILABLE",
                    "Online payment is temporarily unavailable.");
        String quoteHash = hash(encode(quote));
        var tokens = jdbc.query("SELECT cart_version,token_hash,quote_hash,expires_at FROM web_checkout_quotes WHERE customer_id=?",
                (rs, row) -> new QuoteBinding(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
                customer.getId());
        if (tokens.isEmpty() || tokens.get(0).version() != input.version()
                || !tokens.get(0).tokenHash().equals(hash(input.token())))
            throw conflict("QUOTE_CHANGED", "Prepare and accept the current quote again.");
        if (!tokens.get(0).expires().isAfter(clock.instant()))
            throw conflict("QUOTE_EXPIRED", "The quote expired. Prepare a fresh quote.");
        if (!tokens.get(0).quoteHash().equals(quoteHash))
            throw conflict("QUOTE_CHANGED", "The quote changed. Review a fresh quote before paying.");
        Instant expiry = quote.date().minusDays(1).atTime(LocalTime.of(cutoffHour, 0)).atZone(IST).toInstant();
        // Do not let Razorpay's 16-minute minimum expiry extend beyond the bake cutoff.
        if (!expiry.isAfter(clock.instant().plusSeconds(17 * 60)))
            throw conflict("QUOTE_EXPIRED", "The payment deadline is too near. Choose a later delivery date.");
        quote.totalAmount().setScale(2, java.math.RoundingMode.UNNECESSARY);
        long orderId = Objects.requireNonNull(jdbc.queryForObject("""
                INSERT INTO orders(customer_id,channel,status,fulfillment_type,source,total_amount,
                delivery_charge,discount_amount,discount_label,batch_discount_amount,batch_discount_label,
                gift_label,credit_applied,delivery_date,delivery_address,delivery_preference,loaf_preference)
                VALUES (?,'WEB','PENDING_CONFIRMATION','DELIVERY','RETAIL',?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, customer.getId(), quote.totalAmount(), quote.deliveryCharge(), quote.discountAmount(),
                quote.discountLabel(), quote.batchDiscountAmount(), quote.batchDiscountLabel(), quote.giftLabel(),
                quote.creditApplied(), quote.date(), input.address(), input.delivery(), input.loaf()));
        jdbc.update("UPDATE orders SET order_number=? WHERE id=?",
                numbers.generate(orderId, LocalDateTime.ofInstant(clock.instant(), IST)), orderId);
        for (var line : quote.lines()) jdbc.update("""
                INSERT INTO order_items(order_id,menu_item_id,quantity,unit_price,subtotal) VALUES (?,?,?,?,?)
                """, orderId, line.itemId(), line.quantity(), line.unitPrice(), line.lineTotal());
        jdbc.update("INSERT INTO payments(order_id,amount,status,provider) VALUES (?,?,'PENDING','RAZORPAY')",
                orderId, quote.totalAmount());
        // 192 random bits; Razorpay reference_id is limited to 40 characters including our prefix.
        byte[] idBytes = new byte[24]; random.nextBytes(idBytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
        var snapshot = mapper.createObjectNode();
        snapshot.put("name", input.name()); snapshot.put("address", input.address());
        snapshot.put("deliveryPreference", input.delivery()); snapshot.put("loafPreference", input.loaf());
        snapshot.set("quote", mapper.valueToTree(quote));
        jdbc.update("""
                INSERT INTO web_checkout_attempts(id,customer_id,order_id,idempotency_key,request_hash,cart_version,
                quote_token_hash,quote_hash,snapshot,state,provider_reference,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,'READY',?,?)
                """, id, customer.getId(), orderId, input.key(), requestHash, input.version(), hash(input.token()),
                quoteHash, encode(snapshot), "web_" + id, Timestamp.from(expiry));
        return new Acceptance(id, true);
    }

    private record QuoteBinding(long version, String tokenHash, String quoteHash, Instant expires) { }

    private void dispatch(String id) {
        Dispatch dispatch = tx.execute(status -> {
            capacity.acquire();
            int claimed = jdbc.update("""
                    UPDATE web_checkout_attempts SET state='CREATING',updated_at=clock_timestamp()
                    WHERE id=? AND state='READY' AND expires_at>clock_timestamp()
                    AND EXISTS(SELECT 1 FROM orders o WHERE o.id=order_id AND o.status='PENDING_CONFIRMATION')
                    """, id);
            if (claimed != 1) return null;
            return jdbc.queryForObject("""
                    SELECT a.id,a.order_id,a.provider_reference,a.snapshot->>'name',c.phone,p.amount,a.expires_at
                    FROM web_checkout_attempts a JOIN customers c ON c.id=a.customer_id
                    JOIN payments p ON p.order_id=a.order_id WHERE a.id=?
                    """, (rs, row) -> new Dispatch(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getBigDecimal(6), rs.getTimestamp(7).toInstant()), id);
        });
        if (dispatch == null) return;
        RazorpayService.PaymentLink link;
        try {
            link = razorpay.createPaymentLink(dispatch.amount(), "Tranche Bakery Order " + dispatch.orderId(),
                    dispatch.reference(), dispatch.name(), dispatch.phone(), Map.of("kind", "ORDER",
                    "orderId", String.valueOf(dispatch.orderId()), "webAttemptId", id,
                    "webReference", dispatch.reference()), dispatch.expires());
        } catch (Exception ex) {
            unknown(id);
            return; // Ambiguous outcome: never retry create, never offer QR fallback.
        }
        if (link == null || link.id() == null || !link.id().matches("plink_[A-Za-z0-9_]{1,94}")
                || !safeUrl(link.shortUrl())) {
            unknown(id);
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                capacity.acquire();
                var rows = jdbc.query("""
                        SELECT a.provider_link_id,p.gateway_link_id,p.status,o.status,o.cutoff_cancelled
                        FROM web_checkout_attempts a JOIN payments p ON p.order_id=a.order_id
                        JOIN orders o ON o.id=a.order_id WHERE a.id=? FOR UPDATE OF a,p,o
                        """, (rs, row) -> new LinkBinding(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5)), id);
                if (rows.isEmpty()) return;
                LinkBinding saved = rows.get(0);
                if ((saved.attemptLink() != null && !saved.attemptLink().equals(link.id()))
                        || (saved.paymentLink() != null && !saved.paymentLink().equals(link.id()))) {
                    jdbc.update("UPDATE web_checkout_attempts SET state='UNKNOWN',updated_at=clock_timestamp() WHERE id=?", id);
                    jdbc.update("UPDATE payments SET status='REVIEW_REQUIRED' WHERE order_id=?", dispatch.orderId());
                    return;
                }
                // Preserve webhook-before-persistence capture; never reset a payment to PENDING.
                jdbc.update("""
                        UPDATE web_checkout_attempts SET provider_link_id=?,provider_url=?,state='PAYABLE',
                        updated_at=clock_timestamp() WHERE id=?
                        """, link.id(), link.shortUrl(), id);
                jdbc.update("UPDATE payments SET gateway_link_id=?,gateway_link_url=?,updated_at=now() WHERE order_id=?",
                        link.id(), link.shortUrl(), dispatch.orderId());
            });
        } catch (RuntimeException ex) {
            unknown(id); // Commit ambiguity is also not permission to create another link.
        }
        // If cutoff won while the network request was in flight, retire the now-known link.
        expireAndRevoke(id);
    }

    private record LinkBinding(String attemptLink, String paymentLink, String paymentStatus,
                               String orderStatus, boolean cutoffCancelled) { }

    private void unknown(String id) {
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE web_checkout_attempts SET state='UNKNOWN',updated_at=clock_timestamp()
                WHERE id=? AND state='CREATING'
                """, id));
    }

    public Checkout status(String id, HttpServletRequest request) {
        Checkout result = Objects.requireNonNull(tx.execute(status -> {
            capacity.acquire();
            Customer customer = carts.requireCustomer(request);
            View view = owned(id, customer.getId());
            return view(view);
        }));
        // Read is never a create/retry operation. Known expired links may only be revoked.
        if (Set.of("CANCELLED", "EXPIRED").contains(result.status())) expireAndRevoke(id);
        return result;
    }

    private View owned(String id, long customerId) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{16,64}")) throw notFound();
        var rows = jdbc.query("""
                SELECT a.id,a.state,a.provider_url,a.provider_link_id,a.expires_at,a.updated_at,
                o.status,o.cutoff_cancelled,p.status,p.gateway_link_id,p.gateway_payment_id
                FROM web_checkout_attempts a JOIN orders o ON o.id=a.order_id
                JOIN payments p ON p.order_id=o.id WHERE a.id=? AND a.customer_id=?
                """, (rs, row) -> new View(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getBoolean(8),
                rs.getString(9), rs.getString(10), rs.getString(11)), id, customerId);
        if (rows.size() != 1) throw notFound();
        return rows.get(0);
    }

    private Checkout view(View view) {
        String state;
        if (view.cutoffCancelled() || "CANCELLED".equals(view.orderStatus())) state = "CANCELLED";
        else if ("REVIEW_REQUIRED".equals(view.paymentStatus()) || "PAYMENT_REVIEW_REQUIRED".equals(view.orderStatus()))
            state = "REVIEW_REQUIRED";
        else if ("GATEWAY_CAPTURED".equals(view.paymentStatus()) && view.paymentId() != null
                && Set.of("CONFIRMED", "IN_BAKING", "OUT_FOR_DELIVERY", "COMPLETED").contains(view.orderStatus())) state = "PAID";
        else if (!view.expires().isAfter(clock.instant())) state = "EXPIRED";
        else if (Set.of("READY", "CREATING").contains(view.state()) && view.updated().isBefore(clock.instant().minusSeconds(120))) {
            jdbc.update("UPDATE web_checkout_attempts SET state='UNKNOWN',updated_at=clock_timestamp() WHERE id=? AND state IN ('READY','CREATING')",
                    view.id());
            state = "UNKNOWN";
        } else if ("PAYABLE".equals(view.state()) && "PENDING_CONFIRMATION".equals(view.orderStatus())
                && "PENDING".equals(view.paymentStatus()) && Objects.equals(view.link(), view.paymentLink())
                && view.link() != null && safeUrl(view.url())) state = "PAYABLE";
        else state = Set.of("READY", "CREATING").contains(view.state()) ? "CREATING" : "UNKNOWN";
        return new Checkout(view.id(), state, "PAYABLE".equals(state) ? view.url() : null, view.expires());
    }

    /** Expiry is a real cancellation, not merely hiding a still-payable URL. Revoke outside DB tx. */
    private void expireAndRevoke(String id) {
        String link = tx.execute(status -> {
            capacity.acquire();
            jdbc.update("""
                    UPDATE orders SET status='CANCELLED',cutoff_cancelled=true,updated_at=now()
                    WHERE id=(SELECT order_id FROM web_checkout_attempts WHERE id=? AND expires_at<=clock_timestamp())
                    AND status='PENDING_CONFIRMATION'
                    AND NOT EXISTS(SELECT 1 FROM payments p WHERE p.order_id=orders.id AND p.status='GATEWAY_CAPTURED')
                    """, id);
            var links = jdbc.query("""
                    SELECT p.gateway_link_id FROM web_checkout_attempts a JOIN orders o ON o.id=a.order_id
                    JOIN payments p ON p.order_id=o.id WHERE a.id=? AND o.status='CANCELLED'
                    AND p.status='PENDING' AND p.gateway_link_id IS NOT NULL
                    AND p.gateway_link_url IS NOT NULL
                    """, (rs, row) -> rs.getString(1), id);
            return links.isEmpty() ? null : links.get(0);
        });
        if (link == null) return;
        try {
            razorpay.cancelPaymentLink(link);
            tx.executeWithoutResult(status -> {
                capacity.acquire();
                jdbc.update("""
                        UPDATE payments SET gateway_link_url=NULL,updated_at=now() WHERE gateway_link_id=?
                        AND order_id=(SELECT order_id FROM web_checkout_attempts WHERE id=?) AND status='PENDING'
                        """, link, id);
            });
        } catch (Exception ignored) {
            // Keep IDs for reconciliation/revoke retry; never infer payment from cancellation failure.
        }
    }

    public static boolean safeUrl(String value) {
        if (value == null || value.length() > 255) return false;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && Set.of("rzp.io", "razorpay.com").contains(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawFragment() == null && !value.chars().anyMatch(Character::isISOControl);
        } catch (IllegalArgumentException ex) { return false; }
    }

    private StorefrontDtos.Quote validQuote(WebCartService.Cart cart) {
        if (cart.quote() == null) {
            String code = cart.problems().isEmpty() ? "INVALID_CART" : cart.problems().get(0).code();
            throw conflict(code, "Refresh the basket and choose currently available items and a delivery date.");
        }
        if (!cart.quote().valid()) {
            String code = cart.quote().problems().get(0).code();
            throw conflict(code, "The basket is not currently available for checkout.");
        }
        return cart.quote();
    }

    private Input input(JsonNode body) {
        fields(body, FIELDS);
        String key = text(body, "idempotencyKey");
        String token = text(body, "quoteToken");
        if (!key.matches("[A-Za-z0-9_-]{16,80}") || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String name = bounded(body, "name", 1, 100);
        String address = bounded(body, "address", 10, 2000);
        String delivery = text(body, "deliveryPreference"), loaf = text(body, "loafPreference");
        if (!Set.of("GATE", "DOOR", "IN_PERSON").contains(delivery)
                || !Set.of("SLICED", "WHOLE").contains(loaf)) throw invalid();
        return new Input(key, version(body.get("cartVersion")), token, name, address, delivery, loaf);
    }

    private static String bounded(JsonNode body, String field, int min, int max) {
        String raw = text(body, field);
        if (raw.chars().anyMatch(Character::isISOControl)) throw invalid();
        String value = raw.strip();
        if (value.length() < min || value.length() > max) throw invalid();
        return value;
    }
    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }
    private static long version(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 1 || node.longValue() > 9_007_199_254_740_991L) throw invalid();
        return node.longValue();
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || body.size() != allowed.size()) throw invalid();
        var fields = body.fieldNames();
        while (fields.hasNext()) if (!allowed.contains(fields.next())) throw invalid();
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("Checkout unavailable"); }
    }
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Checkout unavailable"); }
    }
    private static StorefrontProblemException invalid() {
        return new StorefrontProblemException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Supply valid checkout details.");
    }
    private static StorefrontProblemException conflict(String code, String message) {
        return new StorefrontProblemException(HttpStatus.CONFLICT, code, message);
    }
    private static StorefrontProblemException notFound() {
        return new StorefrontProblemException(HttpStatus.NOT_FOUND, "CHECKOUT_NOT_FOUND", "Checkout was not found.");
    }
}