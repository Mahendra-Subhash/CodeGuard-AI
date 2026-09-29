package com.codeguardai.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates the client facing failures raised by the service layer into
 * explicit HTTP statuses with a developer readable message.
 *
 * <p>The services report "not found" problems (unknown scan, unknown finding)
 * as {@link IllegalArgumentException}. Reporting those as 404 lets the frontend
 * tell a validation problem apart from a genuine server fault, and it keeps a
 * Java stack trace out of the response body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException exception
    ) {

        String message =
                exception.getMessage();

        Map<String, Object> body =
                new LinkedHashMap<>();

        body.put(
                "error",
                message == null || message.isBlank()
                        ? "The request could not be processed."
                        : message
        );

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(body);
    }
}
