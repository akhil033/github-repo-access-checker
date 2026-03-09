package com.github.accessreport.exception;

// Custom exception to represent the specific case where GitHub's API rate limit has been exceeded. This allows us to capture the retry-after information and return a meaningful response to the caller so they know when to try again.
public class RateLimitExceededException extends RuntimeException {

    // How many seconds until the rate limit resets (from X-RateLimit-Reset header)
    private final long retryAfterSeconds;

    // How many requests are remaining (should be 0 or near-0 at this point)
    private final long remainingRequests;

    // Constructor to create a RateLimitExceededException with a message, retry-after seconds, and remaining request count.
    public RateLimitExceededException(String message, long retryAfterSeconds, long remainingRequests) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.remainingRequests = remainingRequests;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public long getRemainingRequests() {
        return remainingRequests;
    }
}
