package com.github.accessreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.accessreport.exception.GithubApiException;
import com.github.accessreport.exception.RateLimitExceededException;
import com.github.accessreport.model.Repository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubRepoServiceTest {

    private MockWebServer mockWebServer;
    private GithubRepoService repoService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Point WebClient at the MockWebServer's base URL
        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        repoService = new GithubRepoService(webClient, 2); // small per-page to test pagination
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("fetchAllRepositories returns repos from a single page when no Link header is present")
    void fetchAllRepositories_singlePage_returnsAllRepos() throws JsonProcessingException {
        // Arrange: two repositories on one page, no Link: next header
        List<Repository> page1 = List.of(buildRepo(1L, "repo-one"), buildRepo(2L, "repo-two"));

        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(page1))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        List<Repository> result = repoService.fetchAllRepositories("test-org");

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Repository::getName)
                .containsExactlyInAnyOrder("repo-one", "repo-two");
    }

    @Test
    @DisplayName("fetchAllRepositories follows Link: next header and aggregates all pages")
    void fetchAllRepositories_multiplePages_aggregatesAll() throws JsonProcessingException {
        // Arrange: two pages of one repo each, connected by Link header
        List<Repository> page1 = List.of(buildRepo(1L, "repo-one"));
        List<Repository> page2 = List.of(buildRepo(2L, "repo-two"));

        // First response includes a Link: rel="next" pointing to the second page
        String page2Url = mockWebServer.url("/orgs/test-org/repos?page=2&per_page=2").toString();
        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(page1))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .addHeader(HttpHeaders.LINK, "<" + page2Url + ">; rel=\"next\""));

        // Second response has no Link header -> last page
        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(page2))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        List<Repository> result = repoService.fetchAllRepositories("test-org");

        // Assert: both pages merged into one list
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Repository::getName)
                .containsExactlyInAnyOrder("repo-one", "repo-two");
    }

    @Test
    @DisplayName("fetchAllRepositories returns empty list when org has no repositories")
    void fetchAllRepositories_emptyOrg_returnsEmptyList() throws JsonProcessingException {
        // Arrange: GitHub returns an empty array for an org with no repos
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        List<Repository> result = repoService.fetchAllRepositories("empty-org");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("fetchAllRepositories throws RateLimitExceededException on 429 response")
    void fetchAllRepositories_rateLimitExceeded_throwsRateLimitException() {
        // Arrange: GitHub returns 429 with rate limit headers
        long futureEpoch = (System.currentTimeMillis() / 1000) + 300; // 5 minutes from now
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", String.valueOf(futureEpoch))
                .setBody("{\"message\":\"API rate limit exceeded\"}"));

        // Act + Assert
        assertThatThrownBy(() -> repoService.fetchAllRepositories("blocked-org"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("rate limit"); // message check is case-insensitive via contains
    }

    @Test
    @DisplayName("fetchAllRepositories throws GithubApiException on 404 Not Found")
    void fetchAllRepositories_orgNotFound_throwsGithubApiException() {
        // Arrange: org does not exist
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"message\":\"Not Found\"}"));

        // Act + Assert
        assertThatThrownBy(() -> repoService.fetchAllRepositories("nonexistent-org"))
                .isInstanceOf(GithubApiException.class);
    }

    // Helper to build a minimal Repository object for test data
    private Repository buildRepo(Long id, String name) {
        Repository repo = new Repository();
        repo.setId(id);
        repo.setName(name);
        repo.setFullName("test-org/" + name);
        repo.setPrivateRepo(false);
        return repo;
    }
}
