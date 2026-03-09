# GitHub Repository Access Report Service

A Spring Boot microservice that connects to the GitHub REST API and returns a structured report of which users have access to which repositories inside a given GitHub organization.

---

## How to Run

### Prerequisites

- Java 17
- Maven 3.8+
- A GitHub Personal Access Token (see [Authentication](#authentication) below)

### 1. Clone and configure

```bash
git clone https://github.com/akhil033/github-repo-access-checker.git
cd github-repo-access-checker

cp .env.example .env
# Edit .env and set GITHUB_TOKEN and GITHUB_ORG
```

### 2. Run with Maven

```bash
mvn spring-boot:run
```

The service starts on `http://localhost:8080`.

### 3. Run the packaged JAR

```bash
mvn package -DskipTests
java -jar target/github-access-report-1.0.0-SNAPSHOT.jar
```

### 4. Run with Docker Compose

```bash
cp .env.example .env   # fill in GITHUB_TOKEN
docker-compose up --build
```

### Running Tests

```bash
mvn test
```

For testing no real `GITHUB_TOKEN` is needed, tests use MockWebServer to simulate GitHub API responses.

---

## Authentication

The service authenticates to the GitHub API using a **Personal Access Token (PAT)** passed as a Bearer token on every request.

### Required token scopes

| Scope | Purpose |
|-------|---------|
| `repo` | List repositories (use `public_repo` for public-only orgs) |
| `read:org` | Enumerate organization members and their access |

Generate a token at: https://github.com/settings/tokens

### Configuration

Set the token via the `.env` file (loaded automatically by the service):

```
GITHUB_TOKEN=ghp_your_token_here
GITHUB_ORG=your-org-name        # optional default org
```

The `GITHUB_TOKEN` is **required**, the service will refuse to start without it. `GITHUB_ORG` is optional; if set, it acts as a default so you can call the API without passing `?org=` every time.

The token is injected as a `Authorization: Bearer <token>` default header on the `WebClient` bean at startup, no service class handles authentication directly.

---

## API Usage

### GET /api/v1/access-report

Returns a user-to-repository access map for a GitHub organization. Results are cached for 10 minutes.

**Query Parameters**

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `org` | No* | `GITHUB_ORG` env var | GitHub organization login name |
| `refresh` | No | `false` | Bypass cache and fetch fresh data |

\* If `GITHUB_ORG` is set in the environment, `org` is optional.

**Example - using default org from environment:**
```bash
curl "http://localhost:8080/api/v1/access-report"
```

**Example - specifying org explicitly:**
```bash
curl "http://localhost:8080/api/v1/access-report?org=your-org-name"
```

**Example - force refresh (bypass cache):**
```bash
curl "http://localhost:8080/api/v1/access-report?org=your-org-name&refresh=true"
```

**Success Response (200 OK)**
```json
{
  "organization": "your-org-name",
  "generatedAt": "2026-03-09T10:30:00Z",
  "totalRepositories": 2,
  "totalUsersWithAccess": 7,
  "userAccessMap": {
    "alice": [
      { "repository": "backend-service", "permission": "admin", "private": true },
      { "repository": "frontend-app",    "permission": "write", "private": false }
    ],
    "bob": [
      { "repository": "frontend-app",    "permission": "read",  "private": false }
    ]
  },
  "cachedResult": false
}
```

**Error Responses**

| Status | Error code | Cause |
|--------|-----------|-------|
| 400 | `MISSING_REQUIRED_PARAMETER` | No `org` param and `GITHUB_ORG` not set |
| 503 | `GITHUB_RATE_LIMIT_EXCEEDED` | GitHub rate limit hit; includes `retryAfter` seconds |
| 503 | `GITHUB_API_ERROR` | GitHub returned an unexpected error (e.g. 404 org not found) |

### GET /api/v1/health

```bash
curl "http://localhost:8080/api/v1/health"
#  OK
```

### Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## Assumptions & Design Decisions

### Scale: 100+ repositories, 1000+ users

The service is designed to handle organizations of this size without manual tuning:

| Mechanism | How it handles scale |
|-----------|----------------------|
| **Pagination** | `Flux.expand()` follows `rel="next"` Link headers automatically, no hard limit on repo or user count |
| **Parallel collaborator fetch** | `Flux.flatMap(concurrency=10)` fetches 10 repos simultaneously; 100 repos = ~10 rounds, not 100 sequential calls |
| **Per-page=100** | GitHub's maximum page size, minimizing total API call count |
| **Caching** | After the first fetch, all subsequent calls within the 10-minute TTL window are O(1) from Caffeine, zero GitHub API calls |
| **Configurable timeout** | `github.api.report-timeout-seconds` defaults to 300s; a 1000-repo org with 10-concurrent fetches at ~0.5s each needs ~50s; 300s provides ample headroom |
| **Rate limit handling** | 429/403 responses raise a typed `RateLimitExceededException` with the `Retry-After` header surfaced in the error body; remaining-limit warnings are logged when GitHub reports < 10 calls left |

Trade-off acknowledged: repository page fetching is inherently sequential because GitHub's pagination model requires page N's response to obtain page N+1's URL. For a 1000-repo org, that is 10 pages (at per-page=100), a few sequential round-trips before parallel collaborator fetch begins.

### Parallelism for collaborator fetching
Fetching collaborators sequentially for every repo would be very slow. The service uses `Flux.flatMap` with a bounded concurrency limit (default: 10 concurrent calls) to fetch collaborators for all repositories in parallel while staying within GitHub's secondary rate limits.

### Caching
Reports are cached per-organization using Caffeine (default TTL: 10 minutes). The first call fetches live data from GitHub; subsequent calls within the TTL window return instantly from cache. Pass `refresh=true` to force a fresh fetch.

### Pagination
GitHub caps all list endpoints at 100 items per page and uses RFC 5988 `Link` headers to signal further pages. The service follows `rel="next"` links automatically using `Flux.expand()`, no manual page counting required.

### Collaborator affiliation
`affiliation=all` is used when fetching collaborators to include all org members, not just explicitly-added outside collaborators. This produces a complete picture but may show more users than expected since it includes anyone with base org permissions.

### WebClient over RestTemplate
`RestTemplate` is in maintenance mode. `WebClient` supports both synchronous and reactive patterns, enables `Flux`-based parallel fetching, and provides fine-grained per-request timeout control.

### `.env` file loading
Spring Boot does not natively read `.env` files. The service uses `spring.config.import: optional:file:.env[.properties]` in `application.yml` to auto-load the `.env` file when running locally. If you're using .env over real environment variables then this helps in running it without any trouble, or else `.env` is silently ignored.

---

## References

- [Using pagination in the GitHub REST API](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)
- [Rate limits for the GitHub REST API](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [GitHub Collaborators API affiliation behaviour (community discussion)](https://github.com/orgs/community/discussions/77255)
- [Notes on Reactive Programming Spring WebFlux / WebClient](https://spring.io/blog/2016/07/20/notes-on-reactive-programming-part-iii-a-simple-http-server-application/)

---

## License

MIT, see [LICENSE](LICENSE).


---
