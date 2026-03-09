package com.github.accessreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
// DTO representing the API response for the access report. This is what the controller returns to clients.
// It is mapped from the internal AccessReport model, but is decoupled to allow for
public class AccessReportResponse {

    // The GitHub org login this report was generated for
    @JsonProperty("organization")
    private String organization;

    // ISO-8601 UTC timestamp, consumers can use this to judge report freshness
    @JsonProperty("generatedAt")
    private Instant generatedAt;

    // How many repositories were scanned to build this report
    @JsonProperty("totalRepositories")
    private int totalRepositories;

    // How many unique users appear in the report
    @JsonProperty("totalUsersWithAccess")
    private int totalUsersWithAccess;

    // Core data: username- list of {repo, permission, privateRepo}
    @JsonProperty("userAccessMap")
    private Map<String, List<UserRepoAccessDto>> userAccessMap;

    // Indicates whether this response was served from cache.
    // Useful for debugging and for consumers that want to know how fresh the data is.
    @JsonProperty("cachedResult")
    private boolean cachedResult;
}
