package com.tranche.bakery.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class AuthCrypto {
    private final SecureRandom random = new SecureRandom();
    private final WebAuthSettings settings;
    public AuthCrypto(WebAuthSettings settings) { this.settings = settings; }
    String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    String code() { return String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000)); }
    String hash(String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(settings.secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("Authentication unavailable"); }
    }
    boolean matches(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    static boolean validToken(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    static String phone(String value) {
        // Canonical international form only: accept optional '+', never guess a country or strip arbitrary input.
        if (value == null || !value.matches("\\+?[1-9][0-9]{7,14}")) return null;
        return value.startsWith("+") ? value.substring(1) : value;
    }
}