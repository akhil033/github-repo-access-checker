package com.github.accessreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// DTO representing a single repository access entry for a user in the API response. This is the value type in the userAccessMap of AccessReportResponse.   
public class UserRepoAccessDto {

    // Short repo name (not the full org/repo form to keep JSON concise)
    @JsonProperty("repository")
    private String repository;

    // Human-readable permission level as returned by GitHub's role_name field
    @JsonProperty("permission")
    private String permission;

    // Private flag lets consumers quickly identify sensitive repository access
    @JsonProperty("private")
    private boolean privateRepo;
}
