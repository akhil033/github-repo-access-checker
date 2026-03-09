package com.github.accessreport.exception;

import org.springframework.http.HttpStatusCode;
// Custom exception to represent errors from GitHub API calls. This allows us to capture the HTTP status code and response body for better error handling and debugging.
public class GithubApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String responseBody;

    // Constructor used when we have specific status code and response body from a GitHub API failure.
    public GithubApiException(String message, HttpStatusCode statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    // Constructor for more general exceptions where we don't have specific HTTP details (e.g., JSON parsing error).
    public GithubApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
