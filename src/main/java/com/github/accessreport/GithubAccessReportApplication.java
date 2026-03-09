package com.github.accessreport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
// Main application class for the GitHub Access Report service. This is the entry point of the Spring Boot application. 
// The @EnableCaching annotation activates Spring's annotation-driven cache management capability, allowing us to use @Cacheable and related annotations in our service layer to cache access reports and improve performance.
public class GithubAccessReportApplication {
    public static void main(String[] args) {
        SpringApplication.run(GithubAccessReportApplication.class, args);
    }
}
