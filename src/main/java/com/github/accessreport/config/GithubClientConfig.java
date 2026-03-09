package com.github.accessreport.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

 // Design choices:
 // Used Reactor Netty as the underlying HTTP client for fine-grained timeout control (connect + read + write separately).
 // The Bearer token is injected at build time as a default header so no service class ever needs to know about authentication details.
 // A logging filter prints request/response metadata at DEBUG level which helps diagnose pagination and rate-limit issues without exposing tokens.
@Slf4j
@Configuration
 // Configures the WebClient bean used by all GitHub API services.
public class GithubClientConfig {

    // GitHub PAT injected from env var
    @Value("${github.api.token}")
    private String githubToken;

    @Value("${github.api.base-url}")
    private String baseUrl;

    // How many seconds to wait before giving up on a connection attempt
    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    // How many seconds to wait for data after the connection is established
    private static final int READ_TIMEOUT_SECONDS = 10;

    // How many seconds to allow for sending the request
    private static final int WRITE_TIMEOUT_SECONDS = 10;

    // Creates the singleton WebClient configured for the GitHub API.
    // All services should inject this bean rather than creating their own WebClient instances sharing the underlying connection pool is important.
    @Bean
    public WebClient githubWebClient() {
        // Configured Reactor Netty with explicit timeouts
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_SECONDS * 1000)
                .responseTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // GitHub requires version negotiation via Accept header
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                // API version pinning, prevents breaking changes from affecting us silently
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                // Bearer token authentication injected at the client level so service classes don't need to manage auth
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + githubToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // Attach logging filter for request/response tracing
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    // Exchange filter that logs outgoing request details at DEBUG level.
    // Intentionally omits the Authorization header value to avoid leaking tokens in logs.
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("GitHub API request: {} {}", request.method(), request.url());
            return Mono.just(request);
        });
    }
    
    // Exchange filter that logs response status at DEBUG level.
    // Also surfaces unexpected status codes at WARN level for quick troubleshooting.
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().isError()) {
                log.warn("GitHub API responded with error status: {}", response.statusCode());
            } else {
                log.debug("GitHub API response status: {}", response.statusCode());
            }
            return Mono.just(response);
        });
    }
}
