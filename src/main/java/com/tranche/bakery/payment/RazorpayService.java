package com.tranche.bakery.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin Razorpay client: creates hosted Payment Links for orders/subscriptions and verifies
 * incoming webhook signatures. Uses raw HTTPS (no SDK) since we only need two calls.
 */
@Service
@Slf4j
public class RazorpayService {

    private static final String API_BASE = "https://api.razorpay.com/v1";

    /** Razorpay's documented minimum for expire_by is 15 minutes out; a minute of slack avoids a 400. */
    private static final Duration EXPIRY_FLOOR = Duration.ofMinutes(16);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${bakery.payment.razorpay.key-id:}")
    private String keyId;

    @Value("${bakery.payment.razorpay.key-secret:}")
    private String keySecret;

    @Value("${bakery.payment.razorpay.webhook-secret:}")
    private String webhookSecret;

    @Value("${bakery.payment.provider:UPI_QR}")
    private String paymentProvider;

    public boolean isConfigured() {
        return notBlank(keyId) && notBlank(keySecret);
    }

    /**
     * True when payment links are the only channel: the screenshot/QR flow is then disabled everywhere.
     * Selecting RAZORPAY without credentials deliberately keeps the QR flow rather than failing closed.
     */
    public boolean isGatewayMode() {
        return "RAZORPAY".equalsIgnoreCase(paymentProvider) && isConfigured();
    }

    public record PaymentLink(String id, String shortUrl) {}

    /** Live view of a link: status is one of created, partially_paid, paid, expired, cancelled. */
    public record LinkState(String status, BigDecimal amountPaid, String paymentId) {}

    /**
     * Creates a Razorpay Payment Link. notes are echoed back on the webhook so we can route it to the
     * right entity. expireBy may be null for links with no deadline.
     */
    public PaymentLink createPaymentLink(BigDecimal amount, String description, String referenceId,
                                         String customerName, String customerPhone,
                                         Map<String, String> notes, Instant expireBy) throws Exception {
        long paise = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();

        ObjectNode body = mapper.createObjectNode();
        body.put("amount", paise);
        body.put("currency", "INR");
        body.put("accept_partial", false);
        body.put("description", trunc(description, 255));
        if (notBlank(referenceId)) body.put("reference_id", referenceId);
        body.put("reminder_enable", false);
        if (expireBy != null) {
            // Razorpay rejects any expiry under 15 minutes out, so a link issued just before the cutoff
            // outlives it by a few minutes. CutoffJob cancels those explicitly to close the gap.
            long floor = Instant.now().plus(EXPIRY_FLOOR).getEpochSecond();
            body.put("expire_by", Math.max(expireBy.getEpochSecond(), floor));
        }
        ObjectNode customer = body.putObject("customer");
        if (notBlank(customerName)) customer.put("name", customerName);
        if (notBlank(customerPhone)) customer.put("contact", toE164(customerPhone));
        // We deliver the link over WhatsApp ourselves — don't let Razorpay SMS/email the customer.
        ObjectNode notify = body.putObject("notify");
        notify.put("sms", false);
        notify.put("email", false);
        ObjectNode notesNode = body.putObject("notes");
        if (notes != null) notes.forEach(notesNode::put);

        String auth = basicAuth();
        HttpRequest req = HttpRequest.newBuilder(URI.create(API_BASE + "/payment_links"))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Razorpay create link failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonNode json = mapper.readTree(resp.body());
        return new PaymentLink(json.path("id").asText(), json.path("short_url").asText());
    }

    /**
     * Stops a link being payable. Razorpay rejects cancellation of an already-paid or expired link,
     * which is a no-op for us, so callers treat any failure as non-fatal.
     */
    public void cancelPaymentLink(String linkId) throws Exception {
        String auth = basicAuth();
        HttpRequest req = HttpRequest.newBuilder(URI.create(API_BASE + "/payment_links/" + linkId + "/cancel"))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Razorpay cancel link failed: " + resp.statusCode() + " " + resp.body());
        }
    }

    /** Asks Razorpay what actually happened to a link, for payments whose webhook never arrived. */
    public LinkState fetchPaymentLink(String linkId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API_BASE + "/payment_links/" + linkId))
                .header("Authorization", "Basic " + basicAuth())
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Razorpay fetch link failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonNode json = mapper.readTree(resp.body());
        JsonNode amountPaid = json.path("amount_paid");
        JsonNode payments = json.path("payments");
        String paymentId = payments.isArray() && !payments.isEmpty()
                ? payments.get(0).path("payment_id").asText(null)
                : null;
        return new LinkState(json.path("status").asText(""),
                amountPaid.isNumber() ? BigDecimal.valueOf(amountPaid.asLong()).movePointLeft(2) : null,
                paymentId);
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
    }

    /** Constant-time HMAC-SHA256 verification of a Razorpay webhook body against the X-Razorpay-Signature header. */
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        if (!notBlank(webhookSecret) || !notBlank(signatureHeader) || rawBody == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return constantTimeEquals(expected, signatureHeader.trim());
        } catch (Exception e) {
            log.warn("Razorpay signature verification error: {}", e.getMessage());
            return false;
        }
    }

    public boolean webhookConfigured() {
        return notBlank(webhookSecret);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String toE164(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.isEmpty() ? "" : "+" + digits;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
