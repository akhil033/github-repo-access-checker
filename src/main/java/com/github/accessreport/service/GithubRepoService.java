package com.github.accessreport.service;

import com.github.accessreport.exception.GithubApiException;
import com.github.accessreport.exception.RateLimitExceededException;
import com.github.accessreport.model.Repository;
import com.github.accessreport.util.PaginationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
// Service responsible for fetching repositories for a given GitHub organization, including pagination handling.
// This is the first step in the access report generation - we need to know all repos before we can fetch collaborators for each repo.
// Pagination is handled with Flux.expand() which recursively follows "next" links until there are no
public class GithubRepoService {

    private final WebClient webClient;
    private final int perPage;

    // Rate limit threshold - warn when remaining calls drop below this
    private static final int RATE_LIMIT_WARN_THRESHOLD = 10;

    // Constructor injection of WebClient and per-page config. WebClient is pre-configured with auth headers.
    public GithubRepoService(WebClient webClient,
                             @Value("${github.api.per-page:100}") int perPage) {
        this.webClient = webClient;
        this.perPage = perPage;
    }

    // Fetches all repositories for the given organization, handling pagination and rate limit exceptions.
    // Returns a plain List of Repository objects. The method blocks until all pages are fetched and combined.
    public List<Repository> fetchAllRepositories(String orgName) {
        log.info("Starting repository fetch for organization: {}", orgName);

        // Build the initial URL for page 1
        String firstPagePath = String.format("/orgs/%s/repos", orgName);
        String firstPageUrl = PaginationUtil.buildPagedUrl(firstPagePath, 1, perPage);

        List<Repository> allRepos = Mono.just(firstPageUrl)
                // Fetch the first page, then use expand() to recursively follow "next" links
                .flatMap(this::fetchOnePage)
                .expand(entity -> {
                    // expand() will keep producing new Monos as long as we return a non-empty publisher.
                    // Returning Mono.empty() terminates the expansion (no more pages).
                    Optional<String> nextUrl = PaginationUtil.extractNextPageUrl(entity.getHeaders());
                    if (nextUrl.isPresent()) {
                        log.debug("Following pagination link to next page");
                        return fetchOnePage(nextUrl.get());
                    }
                    return Mono.empty(); // all pages exhausted
                })
                // Flatten all pages body lists into a single stream of Repository objects
                .flatMap(entity -> {
                    List<Repository> pageRepos = entity.getBody();
                    if (pageRepos == null || pageRepos.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.fromIterable(pageRepos);
                })
                .collectList()
                .block(); // blocking is intentional here, callers expect a plain List

        int count = (allRepos != null) ? allRepos.size() : 0;
        log.info("Completed repository fetch for org '{}': {} repos found", orgName, count);
        return (allRepos != null) ? allRepos : List.of();
    }

    // Fetches a single page of repositories from the given URL and returns a Mono wrapping the full ResponseEntity (so we can inspect the headers for pagination and rate limiting).
    Mono<ResponseEntity<List<Repository>>> fetchOnePage(String url) {
        log.debug("Fetching repositories page: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                // Map rate limit errors (403, 429) to our typed exception
                .onStatus(status -> status.value() == 403 || status.value() == 429,
                        response -> {
                            String remaining = response.headers().asHttpHeaders()
                                    .getFirst("X-RateLimit-Remaining");
                            String resetTime = response.headers().asHttpHeaders()
                                    .getFirst("X-RateLimit-Reset");
                            long retryAfter = computeRetryAfterSeconds(resetTime);
                            long remainingCount = remaining != null ? Long.parseLong(remaining) : 0L;
                            return Mono.error(new RateLimitExceededException(
                                    "GitHub rate limit exceeded while fetching repos",
                                    retryAfter, remainingCount));
                        })
                // Map all other 4xx and 5xx errors to GithubApiException
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GithubApiException(
                                        "GitHub API error fetching repos: " + response.statusCode(),
                                        response.statusCode(), body))))
                .toEntityList(Repository.class)
                // Inspect rate limit headers on every successful response
                .doOnNext(entity -> checkRateLimitHeaders(entity.getHeaders().getFirst("X-RateLimit-Remaining")));
    }

    // Helper to check rate limit headers on successful responses and log warnings if we're running low.
    private void checkRateLimitHeaders(String remainingHeader) {
        if (remainingHeader == null) {
            return;
        }
        try {
            long remaining = Long.parseLong(remainingHeader);
            if (remaining < RATE_LIMIT_WARN_THRESHOLD) {
                log.warn("GitHub rate limit running low: {} requests remaining", remaining);
            } else {
                log.debug("GitHub rate limit remaining: {}", remaining);
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse X-RateLimit-Remaining: {}", remainingHeader);
        }
    }

    // Computes how many seconds to wait before retrying based on the
    // X-RateLimit-Reset header value (a Unix epoch timestamp in seconds).
    // If the header is absent or unparseable, we fall back to a conservative 60-second wait.
    private long computeRetryAfterSeconds(String resetHeader) {
        if (resetHeader == null) {
            return 60L; // conservative fallback
        }
        try {
            long resetEpoch = Long.parseLong(resetHeader);
            long nowEpoch   = System.currentTimeMillis() / 1000;
            long diff       = resetEpoch - nowEpoch;
            // Never return a negative value; at least 1 second
            return Math.max(diff, 1L);
        } catch (NumberFormatException e) {
            log.warn("Could not parse X-RateLimit-Reset header value: {}", resetHeader);
            return 60L;
        }
    }
}
