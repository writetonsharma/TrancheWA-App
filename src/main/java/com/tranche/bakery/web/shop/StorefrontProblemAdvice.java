package com.tranche.bakery.web.shop;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to this controller so existing WhatsApp/admin error handling is unaffected. */
@RestControllerAdvice(assignableTypes = StorefrontController.class)
public class StorefrontProblemAdvice {
    @ExceptionHandler(StorefrontProblemException.class)
    public ResponseEntity<StorefrontDtos.Error> problem(StorefrontProblemException exception) {
        return response(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<StorefrontDtos.Error> malformedJson() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Supply a valid JSON quote request.");
    }

    private ResponseEntity<StorefrontDtos.Error> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate())
                .body(new StorefrontDtos.Error(false, List.of(new StorefrontDtos.Problem(code, message))));
    }
}