package com.tranche.bakery.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.whatsapp.SendOutcome;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real loopback HTTP/Tomcat smoke test. Provider is mocked: never sends a live OTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.forward-headers-strategy=none",
        "bakery.web.enabled=true", "bakery.web.otp.enabled=true", "bakery.web.cookie.secure=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bakery.web.allowed-origins=https://tranchebakery.in", "bakery.cleanup.enabled=false"
})
@ActiveProfiles("test")
class WebAuthHttpSmokeTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @MockitoBean WhatsAppClient whatsapp;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private String csrf;

    @Test void allFoundationRoutesWorkOverRealHttpWithApprovedTemplateAndRotatingCookies() throws Exception {
        AtomicReference<String> delivered = new AtomicReference<>();
        when(whatsapp.sendAuthenticationCode(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    assertThat((String) invocation.getArgument(1)).isEqualTo("website_login_otp");
                    assertThat((String) invocation.getArgument(2)).isEqualTo("en");
                    delivered.set(invocation.getArgument(3));
                    return SendOutcome.SENT;
                });
        var bootstrap = call("GET", "/api/session", null, false);
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        assertThat(bootstrap.headers().allValues("Set-Cookie")).allSatisfy(header ->
                assertThat(header).contains("HttpOnly", "SameSite=Lax").doesNotContain("Domain="));
        JsonNode session = mapper.readTree(bootstrap.body());
        assertThat(session.path("authenticated").asBoolean()).isFalse();
        csrf = session.path("csrfToken").asText();
        assertThat(call("GET", "/api/catalog", null, false).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/quote", "{\"lines\":[]}", false).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/quote", "{\"lines\":[]}", true).statusCode()).isEqualTo(200);
        var challenge = call("POST", "/api/auth/challenge", "{\"phone\":\"919800009048\"}", true);
        assertThat(challenge.statusCode()).isEqualTo(200);
        JsonNode challengeBody = mapper.readTree(challenge.body());
        assertThat(challengeBody.path("expiresInSeconds").asInt()).isEqualTo(300);
        var verified = call("POST", "/api/auth/verify", mapper.writeValueAsString(Map.of(
                "challengeId", challengeBody.path("challengeId").asText(), "code", delivered.get())), true);
        assertThat(verified.statusCode()).isEqualTo(200);
        JsonNode login = mapper.readTree(verified.body());
        assertThat(login.path("authenticated").asBoolean()).isTrue();
        String oldCsrf = csrf;
        csrf = login.path("csrfToken").asText();
        assertThat(csrf.equals(oldCsrf)).isFalse();
        assertThat(call("POST", "/api/quote", "{\"lines\":[]}", true).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(call("GET", "/api/session", null, false).body())
                .path("authenticated").asBoolean()).isTrue();
        var logout = call("POST", "/api/auth/logout", "", true);
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(logout.body()).path("authenticated").asBoolean()).isFalse();
        assertThat(mapper.readTree(call("GET", "/api/session", null, false).body())
                .path("authenticated").asBoolean()).isFalse();

        var spoofed = client.send(HttpRequest.newBuilder(uri("/api/session"))
                .header("Origin", "https://evil.example")
                .header("X-Forwarded-Host", "evil.example").header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", "192.0.2.99").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.statusCode()).isEqualTo(403);
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private HttpResponse<String> call(String method, String path, String body, boolean withCsrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
        if (withCsrf) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        return response;
    }
}