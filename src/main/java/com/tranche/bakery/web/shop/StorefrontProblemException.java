package com.tranche.bakery.web.shop;

import org.springframework.http.HttpStatus;

final class StorefrontProblemException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final HttpStatus status;
    private final String code;

    StorefrontProblemException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() { return status; }
    String code() { return code; }
}