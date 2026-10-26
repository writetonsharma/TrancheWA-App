package com.tranche.bakery.web.auth;

import com.tranche.bakery.customer.Customer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final OtpService otp;
    private final CustomerSessionService sessions;
    private final AuthCookies cookies;
    private final CookieCsrfTokenRepository csrfRepository;
    public AuthController(OtpService otp, CustomerSessionService sessions, AuthCookies cookies,
            CookieCsrfTokenRepository customerCsrfRepository) {
        this.otp = otp; this.sessions = sessions; this.cookies = cookies;
        this.csrfRepository = customerCsrfRepository;
    }
    public record CustomerView(Long id, String name) {
        static CustomerView of(Customer customer) { return customer == null ? null : new CustomerView(customer.getId(), customer.getName()); }
    }
    public record SessionView(boolean authenticated, CustomerView customer, String csrfHeaderName, String csrfToken) {}
    public record ChallengeInput(@NotNull @Size(max = 16) String phone) {}
    public record VerifyInput(@NotNull @Size(max = 64) String challengeId, @NotNull @Size(max = 32) String code) {}
    public record ErrorView(String message) {}

    @GetMapping("/session")
    public SessionView session(HttpServletRequest request, HttpServletResponse response, CsrfToken csrf) {
        cookies.browser(request, response);
        Customer customer = sessions.currentCustomer(request);
        return view(customer, csrf);
    }
    @PostMapping("/auth/challenge")
    public OtpService.Challenge challenge(@Valid @RequestBody ChallengeInput input,
            HttpServletRequest request, HttpServletResponse response) {
        return otp.challenge(input.phone(), cookies.browser(request, response), request.getRemoteAddr());
    }
    @PostMapping("/auth/verify")
    public SessionView verify(@Valid @RequestBody VerifyInput input, HttpServletRequest request,
            HttpServletResponse response, CsrfToken csrf) {
        var verified = otp.verify(input.challengeId(), input.code(), AuthCookies.read(request, AuthCookies.BROWSER),
                AuthCookies.read(request, AuthCookies.SESSION), request.getRemoteAddr());
        cookies.write(response, AuthCookies.SESSION, verified.token(), WebAuthSettings.ABSOLUTE_SECONDS);
        cookies.write(response, AuthCookies.BROWSER, AuthCookies.read(request, AuthCookies.BROWSER), WebAuthSettings.ABSOLUTE_SECONDS);
        return view(verified.customer(), rotateCsrf(request, response));
    }
    @PostMapping("/auth/logout")
    public SessionView logout(HttpServletRequest request, HttpServletResponse response, CsrfToken csrf) {
        otp.invalidateBrowser(AuthCookies.read(request, AuthCookies.BROWSER));
        sessions.revoke(AuthCookies.read(request, AuthCookies.SESSION));
        cookies.write(response, AuthCookies.SESSION, "", 0);
        cookies.write(response, AuthCookies.BROWSER, "", 0);
        return view(null, rotateCsrf(request, response));
    }
    private CsrfToken rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken replacement = csrfRepository.generateToken(request);
        csrfRepository.saveToken(replacement, request, response);
        return replacement;
    }
    private SessionView view(Customer customer, CsrfToken csrf) {
        return new SessionView(customer != null, CustomerView.of(customer), csrf.getHeaderName(), csrf.getToken());
    }
    @ExceptionHandler(OtpService.AuthFailure.class)
    ResponseEntity<ErrorView> failure(OtpService.AuthFailure ex) {
        return ResponseEntity.status(ex.status).body(new ErrorView("Verification unavailable or invalid. Please try again later."));
    }
    // Prevent default binding/parse diagnostics from echoing submitted authentication material.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorView> malformedInput() {
        return ResponseEntity.badRequest().body(new ErrorView("Verification unavailable or invalid. Please try again later."));
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorView> storageUnavailable() {
        return ResponseEntity.status(503).body(new ErrorView("Verification unavailable or invalid. Please try again later."));
    }
}