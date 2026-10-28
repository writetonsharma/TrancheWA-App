package com.tranche.bakery.web.auth;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranche.bakery.customer.Customer;
import com.tranche.bakery.web.shop.StorefrontCatalogService;
import com.tranche.bakery.web.shop.StorefrontCustomerContext;
import com.tranche.bakery.whatsapp.WhatsAppClient;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Checkout remains default-off even with API on; OTP remains independently off. */
@SpringBootTest(properties = {"bakery.web.enabled=true", "bakery.web.otp.enabled=false",
        "bakery.web.checkout.enabled=false", "bakery.web.allowed-origins=https://tranchebakery.in",
        "bakery.web.auth-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "bakery.cleanup.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebCheckoutDisabledTest {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerSessionService sessions;
    @MockitoBean WhatsAppClient whatsapp;
        @MockitoSpyBean StorefrontCatalogService catalog;
        @Autowired ObjectMapper mapper;

    @Test void separateFlagDisablesAllNewRoutesBeforeAuthCsrfAndPersistence() throws Exception {
        mvc.perform(get("/api/cart")).andExpect(status().isNotFound());
        mvc.perform(put("/api/cart").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/checkout").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/checkout/opaque")).andExpect(status().isNotFound());
        for (String path : java.util.List.of("/api/account", "/api/account/profile", "/api/account/location", "/api/account/orders",
                "/api/account/orders/opaque", "/api/account/checkout", "/api/account/unknown",
                "/api/subscriptions", "/api/subscriptions/plans", "/api/subscriptions/current",
                "/api/subscriptions/current/cancel")) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", "no-store, private"));
            mvc.perform(put(path).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/catalog")).andExpect(status().isOk());
        verifyNoInteractions(sessions, whatsapp);
    }

        // Keep the real cookie repository; csrf() can replace it in a cached filter chain.
        MockHttpServletRequestBuilder preview(String path, String body) {
                String token = java.util.UUID.randomUUID().toString();
                return post(path).cookie(new Cookie("TRANCHE_WEB_CSRF", token))
                                .header("X-XSRF-TOKEN", token).contentType("application/json").content(body);
        }

        @Test void shoppingAndQuoteStayPublicWithCheckoutDisabledAndNeverCacheSuccess() throws Exception {
                for (String body : List.of("{}", "{\"date\":null}")) {
                        mvc.perform(preview("/api/shopping", body).header("Origin", "https://tranchebakery.in"))
                                        .andExpect(status().isOk())
                                        .andExpect(header().string("Cache-Control", "no-store, private"))
                                        .andExpect(header().string("Pragma", "no-cache"))
                                        .andExpect(header().string("Access-Control-Allow-Origin", "https://tranchebakery.in"))
                                        .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                                        .andExpect(header().doesNotExist("Location"))
                                        .andExpect(jsonPath("$.pricingMode").value("SINGLE_ITEM_ESTIMATE"))
                                        .andExpect(jsonPath("$.items").isArray())
                                        .andExpect(jsonPath("$.dates").isArray());
                }
                var response = mvc.perform(preview("/api/shopping", "{}"))
                                .andExpect(status().isOk()).andReturn().getResponse();
                String date = mapper.readTree(response.getContentAsString()).path("dates").get(0).path("date").asText();
                mvc.perform(preview("/api/shopping", "{\"date\":\"" + date + "\"}"))
                                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value(date))
                                .andExpect(header().string("Cache-Control", "no-store, private"));
                mvc.perform(preview("/api/quote", "{\"lines\":[]}"))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store, private"));
                verify(catalog, times(4)).shopping(any(), isNull());
                verify(catalog).quote(any(), isNull());
                verifyNoInteractions(whatsapp);
        }

        @Test void previewCsrfAndOriginFailuresStopBeforeIdentityAndCatalogAndAreNotCached() throws Exception {
                for (String path : List.of("/api/shopping", "/api/quote")) {
                        String body = path.endsWith("shopping") ? "{}" : "{\"lines\":[]}";
                        for (var request : List.of(
                                        post(path).contentType("application/json").content(body),
                                        post(path).cookie(new Cookie("TRANCHE_WEB_CSRF", "cookie-token"))
                                                        .contentType("application/json").content(body),
                                        post(path).header("X-XSRF-TOKEN", "header-only")
                                                        .contentType("application/json").content(body),
                                            post(path).cookie(new Cookie("TRANCHE_WEB_CSRF", "cookie-token"))
                                                    .header("X-XSRF-TOKEN", "mismatch").contentType("application/json").content(body),
                                        preview(path, body).header("Origin", "https://evil.example"))) {
                                mvc.perform(request).andExpect(status().isForbidden())
                                                .andExpect(header().string("Cache-Control", "no-store, private"))
                                                .andExpect(header().string("Pragma", "no-cache"))
                                                .andExpect(header().doesNotExist("Location"));
                        }
                }
                verifyNoInteractions(sessions, catalog, whatsapp);
        }

        @Test void shoppingRejectsNonObjectsUnknownFieldsAndNonIsoDatesWithoutCachingErrors() throws Exception {
                for (String body : List.of("null", "[]", "true", "\"2026-10-30\"", "{",
                                "{\"lines\":[]}", "{\"customerId\":123}", "{\"phone\":\"919800000001\"}",
                                "{\"date\":false}", "{\"date\":42}", "{\"date\":{}}", "{\"date\":[]}",
                                "{\"date\":\"2026-02-30\"}", "{\"date\":\"2026-1-01\"}",
                                "{\"date\":\"2026-10-30T00:00:00Z\"}", "{\"date\":\"9999-12-31\"}")) {
                        mvc.perform(preview("/api/shopping", body)).andExpect(status().isBadRequest())
                                        .andExpect(header().string("Cache-Control", "no-store, private"))
                                        .andExpect(header().string("Pragma", "no-cache"))
                                        .andExpect(jsonPath("$.valid").value(false))
                                        .andExpect(jsonPath("$.problems[0].code").value(
                                                        body.contains("\"date\"") ? "INVALID_DATE" : "INVALID_REQUEST"));
                }
                mvc.perform(preview("/api/shopping", "")).andExpect(status().isBadRequest())
                                .andExpect(header().string("Cache-Control", "no-store, private"))
                                .andExpect(jsonPath("$.problems[0].code").value("INVALID_REQUEST"));
                verifyNoInteractions(whatsapp);
        }

        @Test void bothPreviewPathsUseVerifiedIdentityAndDiscardPrepopulatedSpoofAttributes() throws Exception {
                Customer verified = new Customer();
                verified.setId(123L);
                Customer spoof = new Customer();
                spoof.setId(456L);
                for (String path : List.of("/api/shopping", "/api/quote")) {
                        String body = path.endsWith("shopping") ? "{}" : "{\"lines\":[]}";
                        // Return a harmless DTO while verifying the real filter/controller identity handoff.
                        doReturn(null).when(catalog).shopping(any(), any());
                        doReturn(null).when(catalog).quote(any(), any());
                        when(sessions.currentCustomer(any())).thenReturn(verified);
                        mvc.perform(preview(path, body).requestAttr(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, spoof)
                                        .with(user("admin").roles("ADMIN")).header("X-Customer-Id", "456")
                                        .param("customerId", "456"))
                                        .andExpect(status().isOk())
                                        .andExpect(header().string("Cache-Control", "no-store, private"));
                        if (path.endsWith("shopping")) verify(catalog).shopping(any(), same(verified));
                        else verify(catalog).quote(any(), same(verified));
                        clearInvocations(catalog);
                        when(sessions.currentCustomer(any())).thenReturn(null);
                        mvc.perform(preview(path, body).requestAttr(StorefrontCustomerContext.CUSTOMER_ATTRIBUTE, spoof)
                                        .with(user("admin").roles("ADMIN")).header("X-Customer-Id", "456")
                                        .param("customerId", "456"))
                                        .andExpect(status().isOk())
                                        .andExpect(header().string("Cache-Control", "no-store, private"));
                        if (path.endsWith("shopping")) verify(catalog).shopping(any(), isNull());
                        else verify(catalog).quote(any(), isNull());
                        clearInvocations(catalog);
                }
                verifyNoInteractions(whatsapp);
        }

        @Test void optionalIdentityBackendFailureIsClosedAndNotCachedOnBothPaths() throws Exception {
                for (RuntimeException failure : List.of(
                                new org.springframework.dao.DataAccessResourceFailureException("private SQL detail"),
                                new org.springframework.transaction.CannotCreateTransactionException("private transaction detail"))) {
                            doThrow(failure).when(sessions).currentCustomer(any());
                        for (String path : List.of("/api/shopping", "/api/quote")) {
                                mvc.perform(preview(path, path.endsWith("shopping") ? "{}" : "{\"lines\":[]}"))
                                                .andExpect(status().isServiceUnavailable())
                                                .andExpect(header().string("Cache-Control", "no-store, private"))
                                                .andExpect(header().string("Pragma", "no-cache"))
                                                .andExpect(header().doesNotExist("Location"))
                                                .andExpect(content().string(""));
                        }
                }
                verifyNoInteractions(catalog, whatsapp);
        }
}