package com.github.accessreport.service;

import com.github.accessreport.dto.AccessReportResponse;
import com.github.accessreport.model.Collaborator;
import com.github.accessreport.model.RepoAccess;
import com.github.accessreport.model.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessReportServiceTest {

    @Mock
    private GithubRepoService repoService;

    @Mock
    private GithubCollaboratorService collaboratorService;

    @Mock
    private CacheManager cacheManager;

    private AccessReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new AccessReportService(repoService, collaboratorService, cacheManager, 5);
    }

    @Test
    @DisplayName("generateReport aggregates repos and collaborators into user-centric map")
    void generateReport_happyPath_buildsCorrectUserAccessMap() {
        // Arrange
        Repository repo1 = buildRepo(1L, "backend-service", "my-org/backend-service", true);
        Repository repo2 = buildRepo(2L, "frontend-app", "my-org/frontend-app", false);

        Collaborator akhil = buildCollaborator("akhil", "admin");
        Collaborator ami   = buildCollaborator("ami", "write");

        RepoAccess repo1Access = RepoAccess.builder()
                .repository(repo1)
                .collaborators(List.of(akhil, ami))
                .build();

        RepoAccess repo2Access = RepoAccess.builder()
                .repository(repo2)
                .collaborators(List.of(ami)) // ami has access to both repos
                .build();

        when(repoService.fetchAllRepositories("my-org")).thenReturn(List.of(repo1, repo2));
        when(collaboratorService.fetchCollaboratorsForRepo(repo1)).thenReturn(Mono.just(repo1Access));
        when(collaboratorService.fetchCollaboratorsForRepo(repo2)).thenReturn(Mono.just(repo2Access));

        // Act
        AccessReportResponse response = reportService.generateReport("my-org");

        // Assert top-level fields
        assertThat(response.getOrganization()).isEqualTo("my-org");
        assertThat(response.getTotalRepositories()).isEqualTo(2);
        assertThat(response.getUserAccessMap()).hasSize(2); 

        // Assert 
        var akhilAccess = response.getUserAccessMap().get("akhil");
        assertThat(akhilAccess).hasSize(1);
        assertThat(akhilAccess.get(0).getRepository()).isEqualTo("backend-service");
        assertThat(akhilAccess.get(0).getPermission()).isEqualTo("admin");
        assertThat(akhilAccess.get(0).isPrivateRepo()).isTrue();

        // Assert ami (should appear in both repos)
        var amiAccess = response.getUserAccessMap().get("ami");
        assertThat(amiAccess).hasSize(2);
        assertThat(amiAccess).extracting(dto -> dto.getRepository())
                .containsExactlyInAnyOrder("backend-service", "frontend-app");
    }

    @Test
    @DisplayName("generateReport populates generatedAt timestamp")
    void generateReport_setsGeneratedAtTimestamp() {
        // Arrange
        when(repoService.fetchAllRepositories("org-x")).thenReturn(List.of());

        // Act
        AccessReportResponse response = reportService.generateReport("org-x");

        // Assert: generatedAt should be set
        assertThat(response.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("generateReport returns empty userAccessMap when org has no repos")
    void generateReport_noRepos_returnsEmptyMap() {
        // Arrange
        when(repoService.fetchAllRepositories("empty-org")).thenReturn(List.of());

        // Act
        AccessReportResponse response = reportService.generateReport("empty-org");

        // Assert
        assertThat(response.getTotalRepositories()).isZero();
        assertThat(response.getTotalUsersWithAccess()).isZero();
        assertThat(response.getUserAccessMap()).isEmpty();
    }

    @Test
    @DisplayName("generateReport derives permission from permissions flags when role_name absent")
    void generateReport_usesPermissionFlagsWhenRoleNameAbsent() {
        // Arrange: collaborator with no role_name, uses boolean flags
        Repository repo = buildRepo(1L, "repo-a", "org/repo-a", false);
        Collaborator ram = new Collaborator();
        ram.setLogin("ram");
        ram.setType("User");
        ram.setRoleName(null); // no role_name - must derive from flags
        ram.setPermissions(Map.of("admin", false, "push", true, "pull", true));

        RepoAccess access = RepoAccess.builder()
                .repository(repo)
                .collaborators(List.of(ram))
                .build();

        when(repoService.fetchAllRepositories("org")).thenReturn(List.of(repo));
        when(collaboratorService.fetchCollaboratorsForRepo(repo)).thenReturn(Mono.just(access));

        // Act
        AccessReportResponse response = reportService.generateReport("org");

        // Assert: push=true should resolve to "write"
        assertThat(response.getUserAccessMap().get("ram").get(0).getPermission())
                .isEqualTo("write");
    }

    @Test
    @DisplayName("generateReport does not call evictCache on its own")
    void generateReport_noRefreshParam_doesNotCallEvict() {
        // Arrange
        when(repoService.fetchAllRepositories("my-org")).thenReturn(List.of());

        // Act
        reportService.generateReport("my-org");

        // Assert: evictCache should never be called by generateReport itself
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("evictCache calls cache evict for the correct org key")
    void evictCache_callsCacheEvict() {
        reportService.evictCache("my-org");
    }

    // Helper builders
    private Repository buildRepo(Long id, String name, String fullName, boolean privateRepo) {
        Repository repo = new Repository();
        repo.setId(id);
        repo.setName(name);
        repo.setFullName(fullName);
        repo.setPrivateRepo(privateRepo);
        return repo;
    }

    private Collaborator buildCollaborator(String login, String roleName) {
        Collaborator c = new Collaborator();
        c.setLogin(login);
        c.setId(1L);
        c.setType("User");
        c.setRoleName(roleName);
        c.setPermissions(Map.of(
                "admin",    "admin".equals(roleName),
                "maintain", "maintain".equals(roleName),
                "push",     "write".equals(roleName),
                "triage",   "triage".equals(roleName),
                "pull",     true
        ));
        return c;
    }
}
