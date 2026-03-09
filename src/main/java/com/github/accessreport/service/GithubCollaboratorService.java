package com.github.accessreport.service;

import com.github.accessreport.exception.GithubApiException;
import com.github.accessreport.exception.RateLimitExceededException;
import com.github.accessreport.model.Collaborator;
import com.github.accessreport.model.Repository;
import com.github.accessreport.model.RepoAccess;
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
// Service responsible for fetching collaborators for a given repository, including pagination handling.
// This service is called by AccessReportService once the full list of repos is known. 
// It is designed to be called in parallel for multiple repos using Flux.flatMap with a concurrency limit to avoid overwhelming the GitHub API.

// Pagination is handled with Flux.expand() in the same pattern used by GithubRepoService for consistency.
public class GithubCollaboratorService {

    private final WebClient webClient;
    private final int perPage;

    private static final int RATE_LIMIT_WARN_THRESHOLD = 10;

    // Constructor injection of WebClient and per-page config. WebClient is pre-configured with auth headers.
    public GithubCollaboratorService(WebClient webClient,
                                     @Value("${github.api.per-page:100}") int perPage) {
        this.webClient = webClient;
        this.perPage = perPage;
    }

    // Fetches all collaborators for a given repository, handling pagination and rate limit exceptions.
    // Returns a Mono of RepoAccess which includes the repository and its list of collaborators.
    // Request affiliation=all to include all org members (not just outside collaborators) so the report is comprehensive.
    public Mono<RepoAccess> fetchCollaboratorsForRepo(Repository repository) {
        log.debug("Fetching collaborators for repository: {}", repository.getFullName());

        // Build initial URL with affiliation=all to catch all members
        String firstPagePath = String.format("/repos/%s/collaborators", repository.getFullName());
        String firstPageUrl = PaginationUtil.buildPagedUrl(firstPagePath, 1, perPage)
                + "&affiliation=all";

        return Mono.just(firstPageUrl)
                .flatMap(this::fetchOnePage)
                .expand(entity -> {
                    Optional<String> nextUrl = PaginationUtil.extractNextPageUrl(entity.getHeaders());
                    if (nextUrl.isPresent()) {
                        return fetchOnePage(nextUrl.get());
                    }
                    return Mono.empty();
                })
                .flatMap(entity -> {
                    List<Collaborator> collaborators = entity.getBody();
                    if (collaborators == null || collaborators.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.fromIterable(collaborators);
                })
                .collectList()
                .map(collaborators -> RepoAccess.builder()
                        .repository(repository)
                        .collaborators(collaborators)
                        .build())
                .doOnSuccess(repoAccess ->
                        log.debug("Found {} collaborators for repo '{}'",
                                repoAccess.getCollaborators().size(), repository.getName()))
                .onErrorMap(ex -> {
                    // Propagate our domain exceptions unchanged; wrap anything else
                    if (ex instanceof RateLimitExceededException || ex instanceof GithubApiException) {
                        return ex;
                    }
                    return new GithubApiException(
                            "Unexpected error fetching collaborators for " + repository.getFullName(), ex);
                });
    }

    // Fetches one page of collaborators from the given URL, handling rate limit exceptions and API errors.
    // Returns a Mono of ResponseEntity<List<Collaborator>> to access both the body and headers for pagination.
    Mono<ResponseEntity<List<Collaborator>>> fetchOnePage(String url) {
        log.debug("Fetching collaborators page: {}", url);

        return webClient.get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.value() == 403 || status.value() == 429,
                        response -> {
                            String remaining = response.headers().asHttpHeaders()
                                    .getFirst("X-RateLimit-Remaining");
                            String resetTime = response.headers().asHttpHeaders()
                                    .getFirst("X-RateLimit-Reset");
                            long retryAfter = computeRetryAfterSeconds(resetTime);
                            long remainingCount = remaining != null ? Long.parseLong(remaining) : 0L;
                            return Mono.error(new RateLimitExceededException(
                                    "GitHub rate limit exceeded while fetching collaborators",
                                    retryAfter, remainingCount));
                        })
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new GithubApiException(
                                        "GitHub API error fetching collaborators: " + response.statusCode(),
                                        response.statusCode(), body))))
                .toEntityList(Collaborator.class)
                .doOnNext(entity -> checkRateLimitHeaders(
                        entity.getHeaders().getFirst("X-RateLimit-Remaining")));
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
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse X-RateLimit-Remaining: {}", remainingHeader);
        }
    }

    // Helper to compute how many seconds until the rate limit resets based on the X-RateLimit-Reset header.
    private long computeRetryAfterSeconds(String resetHeader) {
        if (resetHeader == null) {
            return 60L;
        }
        try {
            long resetEpoch = Long.parseLong(resetHeader);
            long nowEpoch   = System.currentTimeMillis() / 1000;
            return Math.max(resetEpoch - nowEpoch, 1L);
        } catch (NumberFormatException e) {
            log.warn("Could not parse X-RateLimit-Reset header: {}", resetHeader);
            return 60L;
        }
    }
}
