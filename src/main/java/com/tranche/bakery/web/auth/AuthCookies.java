package com.tranche.bakery.web.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    static final String BROWSER = "TRANCHE_WEB_BROWSER";
    static final String SESSION = "TRANCHE_WEB_SESSION";
    private final WebAuthSettings settings;
    private final AuthCrypto crypto;
    public AuthCookies(WebAuthSettings settings, AuthCrypto crypto) { this.settings = settings; this.crypto = crypto; }
    String browser(HttpServletRequest request, HttpServletResponse response) {
        String value = read(request, BROWSER);
        if (!AuthCrypto.validToken(value)) {
            value = crypto.token();
            write(response, BROWSER, value, WebAuthSettings.ABSOLUTE_SECONDS);
        }
        return value;
    }
    static String read(HttpServletRequest request, String name) {
        String value = null;
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                if (value != null) return null; // ambiguous duplicate cookies must never select an identity
                value = cookie.getValue();
            }
        }
        return value;
    }
    void write(HttpServletResponse response, String name, String value, int seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).path("/")
                .httpOnly(true).secure(settings.secure).sameSite("Lax").maxAge(seconds).build().toString());
    }
}