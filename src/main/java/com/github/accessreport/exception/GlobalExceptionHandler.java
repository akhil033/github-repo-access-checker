package com.github.accessreport.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
// Global exception handler to catch and handle exceptions thrown by controllers and services in a centralized way.
// This allows us to return consistent error responses with appropriate HTTP status codes and messages, and to
public class GlobalExceptionHandler {

    // Handles the specific case of GitHub API rate limit being exceeded. Returns 503 with a Retry-After header.
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("GitHub rate limit exceeded. Retry after {} seconds", ex.getRetryAfterSeconds());

        Map<String, Object> body = new HashMap<>();
        body.put("error", "GITHUB_RATE_LIMIT_EXCEEDED");
        body.put("message", ex.getMessage());
        body.put("retryAfter", ex.getRetryAfterSeconds());
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    // Handles general GitHub API errors that are not rate limit related. Returns 503 with error details.
    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<Map<String, Object>> handleGithubApiException(GithubApiException ex) {
        log.error("GitHub API call failed: {}", ex.getMessage(), ex);

        Map<String, Object> body = new HashMap<>();
        body.put("error", "GITHUB_API_ERROR");
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());

        // Include the HTTP status from GitHub if we have it, for debugging
        if (ex.getStatusCode() != null) {
            body.put("upstreamStatus", ex.getStatusCode().value());
        }

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    // Returns 404 for missing static resources (e.g. favicon.ico requested by browsers).
    // Without this, the catch-all Exception handler would log a noisy ERROR and return 500.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(NoResourceFoundException ex) {
        // No logging needed - this is a normal browser behaviour
        return ResponseEntity.notFound().build();
    }

    // Handles the case where a required request parameter is missing. Returns 400 Bad Request with details about the missing parameter.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.debug("Missing required request parameter: {}", ex.getParameterName());

        Map<String, Object> body = new HashMap<>();
        body.put("error", "MISSING_REQUIRED_PARAMETER");
        body.put("message", "Required parameter '" + ex.getParameterName() + "' is missing");
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity.badRequest().body(body);
    }

    // Catch-all handler for any other exceptions that were not specifically handled. Returns 500 Internal Server Error with a generic message.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unexpected error processing request", ex);

        Map<String, Object> body = new HashMap<>();
        body.put("error", "INTERNAL_SERVER_ERROR");
        body.put("message", "An unexpected error occurred. Please check server logs.");
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }
}
