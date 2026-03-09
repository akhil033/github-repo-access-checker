package com.github.accessreport.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;
@Configuration
// Configures the Caffeine in-memory cache used by the access report service.
public class CacheConfig {

    // Default is 10 minutes can override via application.yml or env var
    @Value("${github.cache.ttl-minutes:10}")
    private long ttlMinutes;

    // Maximum number of org reports to keep in memory simultaneously
    @Value("${github.cache.max-size:50}")
    private long maxSize;

    @Bean
    // Defines the CacheManager bean that Spring's caching abstraction will use.
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("accessReports");
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                        .maximumSize(maxSize)
                        // Record stats - useful to expose via Actuator for monitoring
                        .recordStats()
        );
        return manager;
    }
}
