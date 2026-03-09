package com.github.accessreport.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.accessreport.dto.AccessReportResponse;
import com.github.accessreport.dto.UserRepoAccessDto;
import com.github.accessreport.exception.GithubApiException;
import com.github.accessreport.exception.GlobalExceptionHandler;
import com.github.accessreport.exception.RateLimitExceededException;
import com.github.accessreport.service.AccessReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(AccessReportController.class)
@Import(GlobalExceptionHandler.class)
// Unit tests for AccessReportController. Using @WebMvcTest to load only the web layer and mock the service layer with @MockBean. This allows us to test the controller's request handling, response formatting, and exception handling in isolation from the actual business logic of generating access reports.
class AccessReportControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AccessReportService accessReportService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/v1/access-report?org=test-org returns 200 with access report")
    void getAccessReport_validOrg_returns200() throws Exception {
        AccessReportResponse mockResponse = buildMockResponse("test-org");
        when(accessReportService.generateReport("test-org")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "test-org")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.organization").value("test-org"))
                .andExpect(jsonPath("$.totalRepositories").value(2))
                .andExpect(jsonPath("$.totalUsersWithAccess").value(1))
                .andExpect(jsonPath("$.userAccessMap").exists())
                .andExpect(jsonPath("$.userAccessMap.alice").isArray())
                .andExpect(jsonPath("$.userAccessMap.alice[0].repository").value("backend-service"))
                .andExpect(jsonPath("$.userAccessMap.alice[0].permission").value("admin"));

        verify(accessReportService, times(1)).generateReport("test-org");
    }

    @Test
    @DisplayName("GET /api/v1/access-report without org param falls back to default org from config")
    void getAccessReport_missingOrgParam_usesDefaultOrg_returns200() throws Exception {
        // With no ?org= param, the controller falls back to github.api.org from config.
        // The test application.yml sets github.api.org=test-org, so we expect that to be used.
        AccessReportResponse mockResponse = buildMockResponse("test-org");
        when(accessReportService.generateReport("test-org")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/access-report")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("test-org"));

        verify(accessReportService, times(1)).generateReport("test-org");
    }

    @Test
    @DisplayName("GET /api/v1/access-report with blank org param falls back to default org from config")
    void getAccessReport_blankOrgParam_usesDefaultOrg_returns200() throws Exception {
        // A whitespace-only ?org= is treated as blank — controller falls back to
        // github.api.org from config (test-org in test application.yml).
        AccessReportResponse mockResponse = buildMockResponse("test-org");
        when(accessReportService.generateReport("test-org")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "   ")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization").value("test-org"));

        verify(accessReportService, times(1)).generateReport("test-org");
    }

    @Test
    @DisplayName("GET /api/v1/access-report?org=test-org&refresh=true evicts cache then generates report")
    void getAccessReport_refreshTrue_evictsCacheAndFetchesFresh() throws Exception {
        AccessReportResponse mockResponse = buildMockResponse("test-org");
        when(accessReportService.generateReport("test-org")).thenReturn(mockResponse);
        doNothing().when(accessReportService).evictCache("test-org");

        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "test-org")
                        .param("refresh", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        var inOrder = inOrder(accessReportService);
        inOrder.verify(accessReportService).evictCache("test-org");
        inOrder.verify(accessReportService).generateReport("test-org");
    }

    @Test
    @DisplayName("GET /api/v1/access-report?org=test-org without refresh does NOT evict cache")
    void getAccessReport_noRefresh_doesNotEvictCache() throws Exception {
        AccessReportResponse mockResponse = buildMockResponse("test-org");
        when(accessReportService.generateReport("test-org")).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "test-org")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(accessReportService, never()).evictCache(anyString());
    }

    @Test
    @DisplayName("GitHub API error returns 503 with GITHUB_API_ERROR code")
    void getAccessReport_githubApiError_returns503() throws Exception {
        // Arrange: service throws a GitHub API error (e.g., 404 Not Found when org doesn't exist)
        when(accessReportService.generateReport("bad-org"))
                .thenThrow(new GithubApiException("Failed to fetch repos", null, null));

        // Act + Assert
        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "bad-org")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("GITHUB_API_ERROR"));
    }

    @Test
    @DisplayName("Rate limit error returns 503 with retryAfter field")
    void getAccessReport_rateLimitError_returns503WithRetryAfter() throws Exception {
        // Arrange: service throws a rate limit exception with a 60-second retry
        when(accessReportService.generateReport("busy-org"))
                .thenThrow(new RateLimitExceededException(
                        "GitHub rate limit exceeded", 60L, 0L));

        // Act + Assert
        mockMvc.perform(get("/api/v1/access-report")
                        .param("org", "busy-org")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("GITHUB_RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfter").value(60));
    }

    @Test
    @DisplayName("GET /api/v1/health returns 200 OK")
    void health_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }

    // Helper to build a minimal but valid AccessReportResponse for test stubs
    private AccessReportResponse buildMockResponse(String orgName) {
        UserRepoAccessDto accessDto = UserRepoAccessDto.builder()
                .repository("backend-service")
                .permission("admin")
                .privateRepo(true)
                .build();

        return AccessReportResponse.builder()
                .organization(orgName)
                .generatedAt(Instant.now())
                .totalRepositories(2)
                .totalUsersWithAccess(1)
                .userAccessMap(Map.of("alice", List.of(accessDto)))
                .cachedResult(false)
                .build();
    }
}
