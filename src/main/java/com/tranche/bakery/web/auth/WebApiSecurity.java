package com.tranche.bakery.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import com.tranche.bakery.web.shop.StorefrontCustomerContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class WebApiSecurity {
    @Bean
    Clock webAuthClock() { return Clock.systemUTC(); }

    @Bean
    CookieCsrfTokenRepository customerCsrfRepository(WebAuthSettings settings) {
        CookieCsrfTokenRepository csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName("TRANCHE_WEB_CSRF");
        csrf.setCookieCustomizer(cookie -> cookie.path("/").httpOnly(true).secure(settings.secure).sameSite("Lax"));
        return csrf;
    }

    @Bean
    @Order(1)
    SecurityFilterChain customerApiChain(HttpSecurity http, WebAuthSettings settings,
            CookieCsrfTokenRepository customerCsrfRepository, CustomerSessionService sessions,
            @Value("${bakery.web.checkout.enabled:false}") boolean checkoutEnabled) throws Exception {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        http.securityMatcher("/api", "/api/**")
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(config -> config.csrfTokenRepository(customerCsrfRepository).csrfTokenRequestHandler(handler))
                .cors(config -> config.configurationSource(request -> {
                    CorsConfiguration cors = new CorsConfiguration();
                    cors.setAllowedOrigins(settings.origins);
                    cors.setAllowCredentials(true);
                    cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                    cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
                    cors.setMaxAge(600L);
                    return cors;
                }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, ex) -> response.setStatus(403)))
                .addFilterBefore(new ApiGuard(settings, checkoutEnabled), CorsFilter.class)
                .addFilterAfter(new CustomerIdentity(sessions), CsrfFilter.class);
        return http.build();
    }

    // After CSRF validation: only verified opaque cookies may provide quote personalization.
    private static final class CustomerIdentity extends OncePerRequestFilter {
        private final CustomerSessionService sessions;
        CustomerIdentity(CustomerSessionService sessions) { this.sessions = sessions; }
        @Override protected void doFilterInternal(@NonNull HttpServletRequest request,
                @NonNull HttpServletResponse response, @NonNull FilterChain chain)
                throws ServletException, IOException {
            request.removeAttribute(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE);
                if ((request.getContextPath() + "/api/quote").equals(request.getRequestURI())
                    || (request.getContextPath() + "/api/shopping").equals(request.getRequestURI())) {
                try {
                    var customer = sessions.currentCustomer(request);
                    if (customer != null) request.setAttribute(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, customer);
                } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                    response.setStatus(503);
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }

    // Only installed in the API chain, not auto-registered as an application servlet filter.
    private static final class ApiGuard extends OncePerRequestFilter {
        private final WebAuthSettings settings;
        private final boolean checkoutEnabled;
        ApiGuard(WebAuthSettings settings, boolean checkoutEnabled) {
            this.settings = settings; this.checkoutEnabled = checkoutEnabled;
        }
        @Override protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
                throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            if (!settings.enabled) { response.setStatus(404); return; }
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (!checkoutEnabled && ("/api/cart".equals(path) || "/api/checkout".equals(path)
                    || path.startsWith("/api/checkout/") || "/api/account".equals(path)
                    || path.startsWith("/api/account/"))) { response.setStatus(404); return; }
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            String sameOrigin = request.getScheme() + "://" + request.getServerName()
                    + ((request.getServerPort() == 443 && "https".equals(request.getScheme()))
                    || (request.getServerPort() == 80 && "http".equals(request.getScheme())) ? "" : ":" + request.getServerPort());
            if (origin != null && !sameOrigin.equals(origin) && !settings.origins.contains(origin)) {
                response.setStatus(403); return;
            }
            chain.doFilter(request, response);
        }
    }
}