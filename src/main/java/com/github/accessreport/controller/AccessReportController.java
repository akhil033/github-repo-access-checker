package com.github.accessreport.controller;

import com.github.accessreport.dto.AccessReportResponse;
import com.github.accessreport.service.AccessReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Access Report", description = "GitHub organization repository access report API")
// REST controller exposing the GitHub access report endpoint.
public class AccessReportController {

    private final AccessReportService accessReportService;
    private final String defaultOrg;

    public AccessReportController(AccessReportService accessReportService,
                                  @Value("${github.api.org:}") String defaultOrg) {
        this.accessReportService = accessReportService;
        this.defaultOrg = defaultOrg;
    }

        // GET /api/v1/access-report?org={org}&refresh={refresh}
        // Returns a structured report of which users have access to which repositories
        // in the specified GitHub organization. The report is cached for 10 minutes by default.      
    @Operation(
        summary = "Get repository access report for a GitHub organization",
        description = "Fetches all repositories in the organization and returns a user-to-repository access map showing each user's permission level. Results are cached for 10 minutes."
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Access report generated successfully",
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AccessReportResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Missing required 'org' parameter",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
        ),
        @ApiResponse(
            responseCode = "503",
            description = "GitHub API error or rate limit exceeded",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)
        )
    })
    @GetMapping(value = "/access-report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessReportResponse> getAccessReport(
            @Parameter(description = "GitHub organization login name. Defaults to the GITHUB_ORG env var if not provided.", example = "my-org")
            @RequestParam(required = false) String org,

            @Parameter(description = "Force refresh: bypass cache and fetch fresh data from GitHub")
            @RequestParam(defaultValue = "false") boolean refresh) {

        // Resolve org: query param > GITHUB_ORG env var
        String resolvedOrg = (org != null && !org.isBlank()) ? org
                : (!defaultOrg.isBlank() ? defaultOrg : null);

        if (resolvedOrg == null) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Access report requested for org='{}', refresh={}", resolvedOrg, refresh);

        // If refresh is requested, evict the current cached entry before fetching.
        // The next call to generateReport() will then miss the cache and do a full fetch.
        if (refresh) {
            log.info("Cache refresh requested for org: {}", resolvedOrg);
            accessReportService.evictCache(resolvedOrg);
        }

        AccessReportResponse report = accessReportService.generateReport(resolvedOrg);
        return ResponseEntity.ok(report);
    }

    // Simple health check endpoint to verify the service is running.
    @Operation(summary = "Health check", description = "Returns 200 OK if the service is running")
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
