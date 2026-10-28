package com.tranche.bakery.web.auth;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tranche.bakery.admin.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class, properties = {"bakery.web.enabled=false", "admin.password=test-only"})
@Import({WebApiSecurity.class, WebAuthSettings.class, SecurityConfig.class})
class WebAuthDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean OtpService otp;
    @MockitoBean CustomerSessionService sessions;
    @MockitoBean AuthCookies cookies;
    @Test void allDisabledEndpointsFailWithoutRedirectOrServicesEvenWithoutCsrf() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Cache-Control", "no-store, private"));
        mvc.perform(post("/api/auth/challenge").contentType("application/json").content("{\"phone\":\"919800000001\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(options("/api/session").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isNotFound());
        mvc.perform(get("/api/account/profile")).andExpect(status().isNotFound());
        mvc.perform(get("/api/account/location")).andExpect(status().isNotFound());
        mvc.perform(put("/api/account/profile").contentType("application/json").content("{}"))
            .andExpect(status().isNotFound());
        verifyNoInteractions(otp, sessions, cookies);
    }
    @SuppressWarnings("null") // Spring test builders lack complete nullability metadata.
    @Test void shoppingAndQuoteAreHiddenBeforeCsrfOriginOrIdentityWhenWebIsDisabled() throws Exception {
        for (String path : java.util.List.of("/api/shopping", "/api/quote")) {
            mvc.perform(post(path).header("Origin", "https://evil.example")
                    .header("X-Customer-Id", "123")
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist("Location"))
                    .andExpect(header().string("Cache-Control", "no-store, private"))
                    .andExpect(header().string("Pragma", "no-cache"));
            mvc.perform(options(path).header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", "no-store, private"));
        }
        verifyNoInteractions(otp, sessions, cookies);
    }
    @Test void adminStillRedirectsAndWebhookDoesNotRequireCsrf() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/webhook")).andExpect(status().isNotFound()); // no webhook controller in this slice
    }
}