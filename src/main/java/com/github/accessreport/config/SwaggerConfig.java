package com.github.accessreport.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

 // The Swagger UI will be available at: http://localhost:8080/swagger-ui.html
 // The raw OpenAPI JSON is at:          http://localhost:8080/v3/api-docs
 
@Configuration
// Configures the OpenAPI metadata for Swagger UI and API documentation.
public class SwaggerConfig {

     // Produces the top-level OpenAPI object shown in Swagger UI.
     // document the Bearer token security scheme so API consumers can try authenticated calls directly from the browser UI.
    @Bean
    public OpenAPI openAPI() {
        // Name of the security scheme, referenced in the SecurityRequirement below
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("GitHub Repository Access Report API")
                        .description(
                                "Retrieves all repositories in a GitHub organization and aggregates collaborator/permission data into a structured user-to-repository access report. Results are cached in-memory for 10 minutes (configurable)."
                        )
                        .version("1.0")
                        .contact(new Contact()
                                .name("Platform Engineering")
                                .email("platform-eng@example.com"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                // Tell Swagger UI that every operation requires a Bearer token
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "GitHub Personal Access Token. Required scopes: repo, read:org"
                                        )));
    }
}
