package com.github.accessreport.service;

import com.github.accessreport.dto.AccessReportResponse;
import com.github.accessreport.dto.UserRepoAccessDto;
import com.github.accessreport.model.Collaborator;
import com.github.accessreport.model.RepoAccess;
import com.github.accessreport.model.Repository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
// Service responsible for orchestrating the access report generation by calling GithubRepoService and GithubCollaboratorService.
// This service contains the core business logic of aggregating the repo-centric data into the user-centric
public class AccessReportService {

    private final GithubRepoService repoService;
    private final GithubCollaboratorService collaboratorService;
    private final CacheManager cacheManager;
    private final int maxConcurrentCalls;

    // Cache name must match what is configured in CacheConfig
    private static final String CACHE_NAME = "accessReports";

    public AccessReportService(GithubRepoService repoService,
                               GithubCollaboratorService collaboratorService,
                               CacheManager cacheManager,
                               @Value("${github.api.max-concurrent-calls:10}") int maxConcurrentCalls) {
        this.repoService = repoService;
        this.collaboratorService = collaboratorService;
        this.cacheManager = cacheManager;
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    // Main method to generate the access report for a given organization. The result is cached by Spring's caching abstraction.
    @Cacheable(value = CACHE_NAME, key = "#orgName.toLowerCase()")
    public AccessReportResponse generateReport(String orgName) {
        log.info("Generating access report for organization: {}", orgName);
        long startTime = System.currentTimeMillis();

        // Step 1: Fetch all repos for the org (blocking - synchronous)
        List<Repository> repos = repoService.fetchAllRepositories(orgName);
        log.info("Fetched {} repositories for org '{}', now fetching collaborators in parallel",
                repos.size(), orgName);

        // Step 2: Fetch collaborators for all repos in parallel with bounded concurrency.
        // Flux.fromIterable creates a stream of repos; flatMap with maxConcurrentCalls
        // limits how many collaborator-fetch Monos are subscribed to (i.e., in-flight) at once.
        List<RepoAccess> repoAccessList = Flux.fromIterable(repos)
                .flatMap(repo -> collaboratorService.fetchCollaboratorsForRepo(repo), maxConcurrentCalls)
                .collectList()
                .block(Duration.ofSeconds(60)); // 60-second ceiling; prevents indefinite hangs

        if (repoAccessList == null) {
            repoAccessList = List.of();
        }

        // Step 3: Aggregate the flat list of RepoAccess records into the user-centric map.
        // Input:  List<RepoAccess> where each item has 1 repo + N collaborators
        // Output: Map<username, List<UserRepoAccessDto>> where each list entry is a repo+permission
        Map<String, List<UserRepoAccessDto>> userAccessMap = aggregateByUser(repoAccessList);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Access report for '{}' generated in {}ms: {} repos, {} users",
                orgName, elapsed, repos.size(), userAccessMap.size());

        return AccessReportResponse.builder()
                .organization(orgName)
                .generatedAt(Instant.now())
                .totalRepositories(repos.size())
                .totalUsersWithAccess(userAccessMap.size())
                .userAccessMap(userAccessMap)
                .cachedResult(false) // will be overridden to true by Caffeine on cache hits
                .build();
    }

    // Method to evict the cache for a specific organization. This is called by the controller when the "refresh" parameter is true.
    @CacheEvict(value = CACHE_NAME, key = "#orgName.toLowerCase()")
    public void evictCache(String orgName) {
        log.info("Cache evicted for organization: {}", orgName);
    }

    // Helper method to transform the list of RepoAccess (repo-centric) into a user-centric map of access.
    private Map<String, List<UserRepoAccessDto>> aggregateByUser(List<RepoAccess> repoAccessList) {
        // Using HashMap (not LinkedHashMap) because order is not meaningful here;
        // JSON serialization will sort keys alphabetically anyway.
        Map<String, List<UserRepoAccessDto>> userAccessMap = new HashMap<>();

        for (RepoAccess repoAccess : repoAccessList) {
            Repository repo = repoAccess.getRepository();
            List<Collaborator> collaborators = repoAccess.getCollaborators();

            if (collaborators == null || collaborators.isEmpty()) {
                continue;
            }

            for (Collaborator collaborator : collaborators) {
                String username = collaborator.getLogin();

                // Determine the best permission string to display.
                // Prefer role_name (set by newer API version) over computing it from flags.
                String permission = resolvePermission(collaborator);

                UserRepoAccessDto accessDto = UserRepoAccessDto.builder()
                        .repository(repo.getName())
                        .permission(permission)
                        .privateRepo(repo.isPrivateRepo())
                        .build();

                // computeIfAbsent creates the list on first encounter for this username
                userAccessMap.computeIfAbsent(username, k -> new ArrayList<>()).add(accessDto);
            }
        }

        return userAccessMap;
    }

    // Helper method to resolve the permission string for a collaborator, preferring role_name but falling back to permissions flags if role_name is absent.
    private String resolvePermission(Collaborator collaborator) {
        // role_name is the most direct and reliable field
        if (collaborator.getRoleName() != null && !collaborator.getRoleName().isBlank()) {
            return collaborator.getRoleName();
        }
        // Fallback: derive from the boolean flags in priority order
        Map<String, Boolean> perms = collaborator.getPermissions();
        if (perms == null) {
            return "read"; // safest default
        }
        if (Boolean.TRUE.equals(perms.get("admin"))) {
            return "admin";
        }
        if (Boolean.TRUE.equals(perms.get("maintain"))) {
            return "maintain";
        }
        if (Boolean.TRUE.equals(perms.get("push"))) {
            return "write"; // "push" maps to the "write" role in GitHub's new terminology
        }
        if (Boolean.TRUE.equals(perms.get("triage"))) {
            return "triage";
        }
        // pull=true is the least privileged level
        return "read";
    }
}
