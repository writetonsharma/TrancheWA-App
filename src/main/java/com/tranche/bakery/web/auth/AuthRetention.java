package com.tranche.bakery.web.auth;

import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bound obsolete auth data retention without touching customers or commerce. */
@Component
public class AuthRetention {
    private final WebAuthSettings settings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public AuthRetention(WebAuthSettings settings, JdbcTemplate jdbc, Clock webAuthClock) {
        this.settings = settings; this.jdbc = jdbc; this.clock = webAuthClock;
    }
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void prune() {
        if (!settings.enabled) return;
        var now = clock.instant();
        var yesterday = Timestamp.from(now.minusSeconds(86400));
        jdbc.update("DELETE FROM web_otp_challenges WHERE expires_at < ?", yesterday);
        jdbc.update("DELETE FROM web_auth_limits WHERE window_start < ?", yesterday);
        jdbc.update("DELETE FROM web_customer_sessions WHERE expires_at <= ? OR last_seen_at <= ?",
                Timestamp.from(now), Timestamp.from(now.minusSeconds(WebAuthSettings.IDLE_SECONDS)));
    }
}