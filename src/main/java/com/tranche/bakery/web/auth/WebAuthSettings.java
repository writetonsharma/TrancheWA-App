package com.tranche.bakery.web.auth;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebAuthSettings {
    final boolean enabled;
    final boolean otpEnabled;
    final boolean secure;
    final String template;
    final String language;
    final byte[] secret;
    final List<String> origins;
    static final int OTP_SECONDS = 300;
    static final int IDLE_SECONDS = 1800;
    static final int ABSOLUTE_SECONDS = 604800;

    public WebAuthSettings(@Value("${bakery.web.enabled:false}") boolean enabled,
            @Value("${bakery.web.otp.enabled:false}") boolean otpEnabled,
            @Value("${bakery.web.cookie.secure:true}") boolean secure,
            @Value("${bakery.web.auth-secret:}") String secret,
            @Value("${bakery.web.otp.template:}") String template,
            @Value("${bakery.web.otp.language:en}") String language,
            @Value("${bakery.web.allowed-origins:}") String origins) {
        this.enabled = enabled;
        this.otpEnabled = otpEnabled;
        this.secure = secure;
        this.template = template;
        this.language = language;
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(secret); }
        catch (IllegalArgumentException ex) { decoded = new byte[0]; }
        this.secret = decoded;
        if (enabled && decoded.length < 32) {
            throw new IllegalStateException("Web authentication requires a random base64 secret of at least 32 bytes");
        }
        this.origins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String origin : this.origins) {
            URI uri = URI.create(origin);
            if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || (!secure && "http".equals(uri.getScheme())))
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw new IllegalStateException("Web allowed origins must be exact scheme/host/port origins");
            }
        }
    }

    boolean canSend() { return enabled && otpEnabled && !template.isBlank() && !language.isBlank(); }
}