package com.github.accessreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.accessreport.exception.RateLimitExceededException;
import com.github.accessreport.model.Collaborator;
import com.github.accessreport.model.RepoAccess;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubCollaboratorServiceTest {

    private MockWebServer mockWebServer;
    private GithubCollaboratorService collaboratorService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        collaboratorService = new GithubCollaboratorService(webClient, 100);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("fetchCollaboratorsForRepo returns collaborators from a single page")
    void fetchCollaboratorsForRepo_singlePage_returnsCollaborators() throws JsonProcessingException {
        // Arrange
        Repository repo = buildRepo("test-org/backend-service");
        List<Collaborator> collaborators = List.of(
                buildCollaborator("akhil", "admin"),
                buildCollaborator("ami", "write")
        );

        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(collaborators))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        RepoAccess result = collaboratorService.fetchCollaboratorsForRepo(repo).block();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getRepository().getFullName()).isEqualTo("test-org/backend-service");
        assertThat(result.getCollaborators()).hasSize(2);
        assertThat(result.getCollaborators()).extracting(Collaborator::getLogin)
                .containsExactlyInAnyOrder("akhil", "ami");
    }

    @Test
    @DisplayName("fetchCollaboratorsForRepo handles pagination and merges pages")
    void fetchCollaboratorsForRepo_multiplePages_mergesResults() throws JsonProcessingException {
        // Arrange
        Repository repo = buildRepo("test-org/paginated-repo");
        List<Collaborator> page1 = List.of(buildCollaborator("user-1", "admin"));
        List<Collaborator> page2 = List.of(buildCollaborator("user-2", "read"));

        String page2Url = mockWebServer.url("/repos/test-org/paginated-repo/collaborators?page=2")
                .toString();

        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(page1))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .addHeader(HttpHeaders.LINK, "<" + page2Url + ">; rel=\"next\""));

        mockWebServer.enqueue(new MockResponse()
                .setBody(mapper.writeValueAsString(page2))
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        RepoAccess result = collaboratorService.fetchCollaboratorsForRepo(repo).block();

        // Assert: both pages combined
        assertThat(result).isNotNull();
        assertThat(result.getCollaborators()).hasSize(2);
        assertThat(result.getCollaborators()).extracting(Collaborator::getLogin)
                .containsExactlyInAnyOrder("user-1", "user-2");
    }

    @Test
    @DisplayName("fetchCollaboratorsForRepo returns empty list when repo has no collaborators")
    void fetchCollaboratorsForRepo_noCollaborators_returnsEmptyList() throws JsonProcessingException {
        // Arrange
        Repository repo = buildRepo("test-org/solo-repo");
        mockWebServer.enqueue(new MockResponse()
                .setBody("[]")
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));

        // Act
        RepoAccess result = collaboratorService.fetchCollaboratorsForRepo(repo).block();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getCollaborators()).isEmpty();
    }

    @Test
    @DisplayName("fetchCollaboratorsForRepo propagates RateLimitExceededException on 429")
    void fetchCollaboratorsForRepo_rateLimited_propagatesException() throws JsonProcessingException {
        // Arrange
        Repository repo = buildRepo("test-org/rate-limited-repo");
        long futureEpoch = (System.currentTimeMillis() / 1000) + 120;

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .addHeader("X-RateLimit-Remaining", "0")
                .addHeader("X-RateLimit-Reset", String.valueOf(futureEpoch)));

        // Act + Assert using StepVerifier (reactive test utility from project-reactor)
        StepVerifier.create(collaboratorService.fetchCollaboratorsForRepo(repo))
                .expectError(RateLimitExceededException.class)
                .verify();
    }

    @Test
    @DisplayName("Parallel fetch of multiple repos with concurrency limit works correctly")
    void parallelFetch_concurrencyLimited_allReposProcessed() throws JsonProcessingException {
        // Arrange: 3 repos, one collaborator each
        List<Repository> repos = List.of(
                buildRepo("org/repo-1"),
                buildRepo("org/repo-2"),
                buildRepo("org/repo-3")
        );

        // Queue a response for each repo
        for (int i = 1; i <= 3; i++) {
            List<Collaborator> collab = List.of(buildCollaborator("user-" + i, "read"));
            mockWebServer.enqueue(new MockResponse()
                    .setBody(mapper.writeValueAsString(collab))
                    .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        }

        // Act: simulate what AccessReportService does - flatMap with concurrency 2
        List<RepoAccess> results = Flux.fromIterable(repos)
                .flatMap(repo -> collaboratorService.fetchCollaboratorsForRepo(repo), 2)
                .collectList()
                .block();

        // Assert: all 3 repos processed, each with 1 collaborator
        assertThat(results).isNotNull();
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(ra -> ra.getCollaborators().size() == 1);
    }

    // Helper builders for test data

    private Repository buildRepo(String fullName) {
        Repository repo = new Repository();
        String name = fullName.contains("/") ? fullName.split("/")[1] : fullName;
        repo.setId(1L);
        repo.setName(name);
        repo.setFullName(fullName);
        repo.setPrivateRepo(false);
        return repo;
    }

    private Collaborator buildCollaborator(String login, String roleName) {
        Collaborator c = new Collaborator();
        c.setLogin(login);
        c.setId(1L);
        c.setType("User");
        c.setRoleName(roleName);
        c.setPermissions(Map.of("admin", "admin".equals(roleName),
                "push", "write".equals(roleName),
                "pull", true));
        return c;
    }
}
