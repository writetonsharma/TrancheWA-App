package com.tranche.bakery.web.auth;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tranche.bakery.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Checkout remains default-off even with API on; OTP remains independently off. */
@SpringBootTest(properties = {"bakery.web.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "bakery.cleanup.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebCheckoutDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerSessionService sessions;
    @MockitoBean WhatsAppClient whatsapp;

    @Test void separateFlagDisablesAllNewRoutesBeforeAuthCsrfAndPersistence() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isNotFound());
        mvc.perform(put("/api/cart").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/checkout").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/checkout/opaque")).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
        verifyNoInteractions(sessions, whatsapp);
    }
}