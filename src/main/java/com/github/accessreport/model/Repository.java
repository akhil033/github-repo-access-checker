package com.github.accessreport.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
// Represents a single GitHub repository as returned by the GitHub API.
public class Repository {

    // Numeric GitHub-assigned ID - stable even if the repo is renamed
    @JsonProperty("id")
    private Long id;

    // Short name of the repo (e.g., "backend-service")
    @JsonProperty("name")
    private String name;

    // org/repo form (e.g., "my-org/backend-service")
    @JsonProperty("full_name")
    private String fullName;

    // Whether the repository is private - important for the access report
    @JsonProperty("private")
    private boolean privateRepo;

    // Repository description, may be null
    @JsonProperty("description")
    private String description;

    // URL to the repo on GitHub.com
    @JsonProperty("html_url")
    private String htmlUrl;

    // Default branch name (usually "main" or "master")
    @JsonProperty("default_branch")
    private String defaultBranch;

    // ISO-8601 timestamp of when the repo was last pushed to
    @JsonProperty("pushed_at")
    private String pushedAt;
}
