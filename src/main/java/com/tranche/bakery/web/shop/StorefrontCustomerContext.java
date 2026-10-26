package com.tranche.bakery.web.shop;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.tranche.bakery.customer.Customer;

import jakarta.servlet.http.HttpServletRequest;

/** The customer-session filter alone may populate this server-side request attribute. */
@Component
public class StorefrontCustomerContext {
    public static final String CUSTOMER_ATTRIBUTE = "storefront.customer";

    /** Never interpret admin principals, request parameters, headers or body fields as identity. */
    public Customer currentCustomer(HttpServletRequest request) {
        Object value = request.getAttribute(CUSTOMER_ATTRIBUTE);
        if (value == null) return null;
        if (value instanceof Customer customer && customer.getId() != null && customer.getId() > 0) {
            return customer;
        }
        throw new StorefrontProblemException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION",
                "Please sign in again.");
    }
}