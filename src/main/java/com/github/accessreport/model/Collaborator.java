package com.github.accessreport.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
// Represents a single collaborator on a GitHub repository as returned by the GitHub API.
public class Collaborator {

    // The GitHub username (login handle) - used as the key in the access report
    @JsonProperty("login")
    private String login;

    // Numeric user ID - stable even if the user changes their username
    @JsonProperty("id")
    private Long id;

    // Avatar URL, optional - included for potential UI presentation
    @JsonProperty("avatar_url")
    private String avatarUrl;

    // Account type: "User" or "Bot" - useful for filtering bot accounts
    @JsonProperty("type")
    private String type;

    // Raw permission flags map from GitHub, e.g. {"admin":false,"push":true,...}
    @JsonProperty("permissions")
    private Map<String, Boolean> permissions;

    // Resolved role name - this is the canonical permission level string;
    // it is easier to work with than deriving the role from the flags map
    @JsonProperty("role_name")
    private String roleName;
}
