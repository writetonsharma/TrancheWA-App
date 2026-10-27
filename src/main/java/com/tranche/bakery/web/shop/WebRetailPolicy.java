package com.tranche.bakery.web.shop;

import org.springframework.http.HttpStatus;

/** Website-only acceptance guard; never applied to legacy quotes or WhatsApp orders. */
final class WebRetailPolicy {
    static final int LIMIT = 5;

    private WebRetailPolicy() {}

    static void requireLimit(StorefrontDtos.Quote quote, HttpStatus status) {
        if (quote.itemCount() > LIMIT) {
            throw new StorefrontProblemException(status, "RETAIL_LIMIT_EXCEEDED",
                    "Website baskets may contain at most five items. Reduce the basket to continue.");
        }
    }
}