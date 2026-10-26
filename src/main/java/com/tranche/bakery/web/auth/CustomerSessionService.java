package com.tranche.bakery.web.auth;

import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.customer.CustomerRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer identity is ONLY resolved from the opaque web cookie pair, never admin/Spring authentication. */
@Service
public class CustomerSessionService {
    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;
    private final AuthCrypto crypto;
    private final WebAuthSettings settings;
    private final Clock clock;
    public CustomerSessionService(JdbcTemplate jdbc, CustomerRepository customers, AuthCrypto crypto,
            WebAuthSettings settings, Clock webAuthClock) {
        this.jdbc = jdbc; this.customers = customers; this.crypto = crypto; this.settings = settings; this.clock = webAuthClock;
    }

    /** Returns null for anonymous, expired, disabled, invalid or admin-only sessions. Do not serialize this entity. */
    @Transactional
    public Customer currentCustomer(HttpServletRequest request) {
        if (!settings.enabled) return null;
        String token = AuthCookies.read(request, AuthCookies.SESSION);
        String browser = AuthCookies.read(request, AuthCookies.BROWSER);
        if (!AuthCrypto.validToken(token) || !AuthCrypto.validToken(browser)) return null;
        Instant now = clock.instant();
        // Customer first, then session: cleanup holds the same customer lock through cascading
        // deletion. Recheck session validity below after waiting for this lock (logout/expiry).
        var locked = jdbc.query("""
            SELECT c.id FROM customers c JOIN web_customer_sessions s ON s.customer_id = c.id
            WHERE s.token_hash = ? AND s.browser_hash = ? AND s.expires_at > ? AND s.last_seen_at > ?
            FOR UPDATE OF c
            """, (rs, row) -> rs.getLong(1), crypto.hash("session", token), crypto.hash("browser", browser),
            Timestamp.from(now), Timestamp.from(now.minusSeconds(WebAuthSettings.IDLE_SECONDS)));
        if (locked.isEmpty()) return null;
        var ids = jdbc.query("""
            UPDATE web_customer_sessions SET last_seen_at = GREATEST(last_seen_at, ?)
                WHERE token_hash = ? AND browser_hash = ? AND expires_at > ? AND last_seen_at > ?
                RETURNING customer_id
                """, (rs, row) -> rs.getLong(1), Timestamp.from(now), crypto.hash("session", token),
                crypto.hash("browser", browser), Timestamp.from(now),
                Timestamp.from(now.minusSeconds(WebAuthSettings.IDLE_SECONDS)));
            if (ids.isEmpty()) return null;
            jdbc.update("UPDATE customers SET last_web_activity_at = GREATEST(last_web_activity_at, ?) WHERE id = ?",
                LocalDateTime.ofInstant(now, ZoneId.of("Asia/Kolkata")), ids.get(0));
            return customers.findById(Objects.requireNonNull(ids.get(0))).orElse(null);
    }

    void revoke(String token) {
        if (AuthCrypto.validToken(token)) jdbc.update("DELETE FROM web_customer_sessions WHERE token_hash = ?", crypto.hash("session", token));
    }
}