package com.codeguardai.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard relies on a distinguishable status when a finding or scan no
 * longer exists, and it must never receive an internal error body.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler =
            new ApiExceptionHandler();

    @Test
    void notFoundFailuresAreReportedAsClientErrorsWithTheirMessage() {

        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(
                        new IllegalArgumentException(
                                "Finding not found: abc123"
                        )
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(response.getBody())
                .containsEntry("error", "Finding not found: abc123");
    }

    @Test
    void failuresWithoutAMessageStillReturnAReadableError() {

        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(
                        new IllegalArgumentException()
                );

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(String.valueOf(response.getBody().get("error")))
                .isNotBlank();
    }

    @Test
    void theHandlerIsRegisteredAsARestControllerAdvice() {

        assertThat(
                ApiExceptionHandler.class.isAnnotationPresent(
                        RestControllerAdvice.class
                )
        ).isTrue();
    }
}
