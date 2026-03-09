package com.github.accessreport.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// Internal domain model representing the access report for a GitHub organization. This is the core data structure that AccessReportService builds and then maps to AccessReportResponse for the API layer. It contains all the information about which users have access to which repositories, along with metadata about the report generation.
public class AccessReport {

    private String orgName;

    // Timestamp when the report was generated - stored as Instant for precision
    private Instant generatedAt;

    private int totalRepositories;

    private int totalUsersWithAccess;

    // The main payload: maps each GitHub username to the repos they can access.
    // Using a Map here (rather than a list) makes user lookups O(1) and the
    // JSON serialization naturally produces the expected nested structure.
    private Map<String, List<UserRepoEntry>> userAccessMap;

    // Inner value type representing one (repo, permission) tuple for a user. Kept as a nested class because it has no meaning outside this report.
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRepoEntry {
        // Short repo name (not full_name) for brevity
        private String repository;
        // The resolved permission level: admin, maintain, write, triage, or read
        private String permission;
        // Whether the repo is private - important context for access reviews
        private boolean privateRepo;
    }
}
