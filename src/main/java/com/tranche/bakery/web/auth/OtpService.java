package com.tranche.bakery.web.auth;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OtpService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuthCrypto crypto;
    private final WebAuthSettings settings;
    private final WhatsAppClient whatsapp;
    private final CustomerRepository customers;
    private final Clock clock;
    public OtpService(JdbcTemplate jdbc, TransactionTemplate tx, AuthCrypto crypto, WebAuthSettings settings,
            WhatsAppClient whatsapp, CustomerRepository customers, Clock webAuthClock) {
        this.jdbc = jdbc; this.tx = tx; this.crypto = crypto; this.settings = settings;
        this.whatsapp = whatsapp; this.customers = customers; this.clock = webAuthClock;
    }

    public record Challenge(String challengeId, int expiresInSeconds, String message) {}
    public record Verified(Customer customer, String token) {}
    static final String GENERIC = "If the number can receive WhatsApp verification, a code will arrive shortly.";

    Challenge challenge(String input, String browser, String ip) {
        if (!settings.canSend()) throw new AuthFailure(503);
        String phone = AuthCrypto.phone(input);
        if (phone == null) throw new AuthFailure(400);
        String id = crypto.token();
        String code = crypto.code();
        String binding = crypto.hash("browser", browser);
        Instant now = clock.instant();
        Boolean allowed = tx.execute(status -> {
            lock(binding);
            // All counters commit even on a rejection; no unbounded in-memory limiter or restart reset.
            boolean ipOk = budget("ip:" + crypto.hash("ip", ip), 20, now);
            boolean phoneOk = budget("phone:" + crypto.hash("phone", phone), 5, now);
            boolean browserOk = budget("browser:" + binding, 5, now);
            if (!ipOk || !phoneOk || !browserOk) return false;
            jdbc.update("UPDATE web_otp_challenges SET state = 'INVALID' WHERE browser_hash = ? AND state IN ('PENDING','ACTIVE')", binding);
            jdbc.update("""
                    INSERT INTO web_otp_challenges(id,phone,browser_hash,code_hash,expires_at)
                    VALUES (?,?,?,?,?)
                    """, id, phone, binding, crypto.hash("otp", id + ":" + binding + ":" + code),
                    Timestamp.from(now.plusSeconds(WebAuthSettings.OTP_SECONDS)));
            return true;
        });
        if (!Boolean.TRUE.equals(allowed)) throw new AuthFailure(429);
        boolean sent;
        try { sent = whatsapp.sendAuthenticationCode(phone, settings.template, settings.language, code) == SendOutcome.SENT; }
        catch (RuntimeException ex) { sent = false; } // Do not log exception/payload/phone/code.
        int activated = jdbc.update("UPDATE web_otp_challenges SET state = ? WHERE id = ? AND state = 'PENDING' AND expires_at > ?",
                sent ? "ACTIVE" : "INVALID", id, Timestamp.from(clock.instant()));
        if (!sent || activated != 1) {
            jdbc.update("UPDATE web_otp_challenges SET state = 'INVALID' WHERE id = ?", id);
            throw new AuthFailure(503);
        }
        return new Challenge(id, WebAuthSettings.OTP_SECONDS, GENERIC);
    }

    Verified verify(String id, String code, String browser, String previousToken, String ip) {
        if (!settings.enabled) throw new AuthFailure(404);
        if (!AuthCrypto.validToken(id) || !AuthCrypto.validToken(browser)) throw new AuthFailure(400);
        String binding = crypto.hash("browser", browser);
        Instant now = clock.instant();
        Verified result = tx.execute(status -> {
            lock(binding);
            // Verification is also throttled across random challenge IDs and process restarts.
            boolean ipOk = budget("verify-ip:" + crypto.hash("ip", ip), 100, now);
            boolean browserOk = budget("verify-browser:" + binding, 30, now);
            if (!ipOk || !browserOk) return null;
            var rows = jdbc.queryForList("SELECT * FROM web_otp_challenges WHERE id = ? FOR UPDATE", id);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            if (!binding.equals(row.get("browser_hash")) || !"ACTIVE".equals(row.get("state"))) return null;
            int attempts = ((Number) row.get("attempts")).intValue();
            Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
            if (!expires.isAfter(now) || attempts >= 5) {
                jdbc.update("UPDATE web_otp_challenges SET state = 'INVALID' WHERE id = ?", id);
                return null;
            }
            boolean correct = code != null && code.matches("[0-9]{6}")
                    && crypto.matches((String) row.get("code_hash"), crypto.hash("otp", id + ":" + binding + ":" + code));
            jdbc.update("UPDATE web_otp_challenges SET attempts = attempts + 1, state = ? WHERE id = ?",
                    correct ? "USED" : (attempts >= 4 ? "INVALID" : "ACTIVE"), id);
            if (!correct) return null; // Commit failed attempts instead of rolling them back via exception.
            String phone = (String) row.get("phone");
                // Lock/update the customer before sessions, matching cleanup's lock order. Only web
                // activity changes on conflict; login never opens an inbound window or changes existing consent.
                // New customers start opted in like WhatsApp customers; STOP on any broadcast opts them out.
            jdbc.update("""
                    INSERT INTO customers(phone,free_delivery,credit_balance,subscription_eligible,marketing_opt_in,created_at,updated_at,last_web_activity_at)
                    VALUES (?,false,0,false,true,?,?,?) ON CONFLICT(phone) DO UPDATE
                    SET last_web_activity_at = GREATEST(customers.last_web_activity_at, EXCLUDED.last_web_activity_at)
                    """, phone, Timestamp.from(now), Timestamp.from(now), LocalDateTime.ofInstant(now, ZoneId.of("Asia/Kolkata")));
            Customer customer = customers.findByPhone(phone).orElseThrow();
            jdbc.update("DELETE FROM web_customer_sessions WHERE browser_hash = ?", binding);
            if (AuthCrypto.validToken(previousToken))
                jdbc.update("DELETE FROM web_customer_sessions WHERE token_hash = ?", crypto.hash("session", previousToken));
            String token = crypto.token();
            jdbc.update("""
                    INSERT INTO web_customer_sessions(token_hash,customer_id,browser_hash,created_at,last_seen_at,expires_at)
                    VALUES (?,?,?,?,?,?)
                    """, crypto.hash("session", token), customer.getId(), binding, Timestamp.from(now), Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(WebAuthSettings.ABSOLUTE_SECONDS)));
            return new Verified(customer, token);
        });
        if (result == null) throw new AuthFailure(400);
        return result;
    }

    void invalidateBrowser(String browser) {
        if (!AuthCrypto.validToken(browser)) return;
        tx.executeWithoutResult(status -> {
            String binding = crypto.hash("browser", browser);
            lock(binding);
            jdbc.update("DELETE FROM web_customer_sessions WHERE browser_hash = ?", binding);
            jdbc.update("UPDATE web_otp_challenges SET state = 'INVALID' WHERE browser_hash = ? AND state IN ('ACTIVE','PENDING')", binding);
        });
    }
    private void lock(String binding) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", binding);
    }
    private boolean budget(String bucket, int limit, Instant now) {
        return !jdbc.query("""
                INSERT INTO web_auth_limits(bucket,window_start,hits) VALUES (?,?,1)
                ON CONFLICT(bucket) DO UPDATE SET
                hits = CASE WHEN web_auth_limits.window_start <= ? THEN 1 ELSE web_auth_limits.hits + 1 END,
                window_start = CASE WHEN web_auth_limits.window_start <= ? THEN EXCLUDED.window_start ELSE web_auth_limits.window_start END
                WHERE web_auth_limits.window_start <= ? OR web_auth_limits.hits < ?
                RETURNING hits
                """, (rs, index) -> rs.getInt(1), bucket, Timestamp.from(now), Timestamp.from(now.minusSeconds(900)),
                Timestamp.from(now.minusSeconds(900)), Timestamp.from(now.minusSeconds(900)), limit).isEmpty();
    }
    static final class AuthFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        AuthFailure(int status) { super("Verification unavailable or invalid"); this.status = status; }
    }
}