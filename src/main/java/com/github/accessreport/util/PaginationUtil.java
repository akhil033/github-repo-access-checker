package com.github.accessreport.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

@Slf4j
// Utility class for handling pagination of GitHub API responses. The GitHub API uses Link headers to indicate if there are more pages of results, and this class provides methods to extract the next page URL and to build paginated URLs for requests. This is used by both GithubRepoService and GithubCollaboratorService to handle paginated endpoints in a consistent way.
public final class PaginationUtil {

    // Prevent instantiation of this utility class
    private PaginationUtil() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    // Extracts the URL for the next page of results from the Link header, if it exists. The Link header can contain multiple comma-separated links with rel="next", rel="prev", etc. We look for the one with rel="next" and return its URL. If there is no Link header or no rel="next", we return Optional.empty() to indicate there are no more pages.
    public static Optional<String> extractNextPageUrl(HttpHeaders headers) {
        String linkHeader = headers.getFirst(HttpHeaders.LINK);

        if (linkHeader == null || linkHeader.isBlank()) {
            log.debug("No Link header in response - this is the last (or only) page");
            return Optional.empty();
        }

        // The Link header can contain multiple comma-separated entries.
        // We split and look for the one with rel="next".
        for (String part : linkHeader.split(",")) {
            part = part.trim();

            // Each part looks like: <https://...>; rel="next"
            if (part.contains("rel=\"next\"")) {
                // Extract the URL from between the angle brackets
                int start = part.indexOf('<');
                int end = part.indexOf('>');

                if (start != -1 && end != -1 && end > start) {
                    String nextUrl = part.substring(start + 1, end);
                    log.debug("Found next page URL: {}", nextUrl);
                    return Optional.of(nextUrl);
                }
            }
        }

        log.debug("Link header present but no rel=\"next\" found - on the last page");
        return Optional.empty();
    }

    // Helper method to check if there is a next page based on the presence of a next page URL in the Link header. This is a convenience method that can be used in conditional logic when we just want to know if more pages exist without needing the actual URL.
    public static boolean hasNextPage(HttpHeaders headers) {
        return extractNextPageUrl(headers).isPresent();
    }

    // Builds a paginated URL by adding the page and per_page query parameters to the base URL. This is used to construct the initial URL for the first page of results, and can also be used to build URLs for specific pages if needed. The method takes care of properly encoding the URL and query parameters.
    public static String buildPagedUrl(String baseUrl, int page, int perPage) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("page", page)
                .queryParam("per_page", perPage)
                .build()
                .toUriString();
    }
}
