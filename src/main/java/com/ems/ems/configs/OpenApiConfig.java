package com.ems.ems.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI / Swagger UI metadata.
 *
 * Access at:
 *   /swagger-ui.html      (UI)
 *   /v3/api-docs          (JSON)
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name:ems}")
    private String applicationName;

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI emsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("EMS API")
                        .description("Employee Management System - REST API")
                        .version("v1")
                        .contact(new Contact()
                                .name("EMS Platform Team")
                                .email("ems-platform@example.com"))
                        .license(new License()
                                .name("Internal")
                                .url("https://example.com/licenses/internal")))
                .addServersItem(new Server().url("http://localhost:" + serverPort).description("Local"))
                .addServersItem(new Server().url("https://ems-dev.example.internal").description("Dev"))
                .addServersItem(new Server().url("https://ems.example.com").description("Prod"));
    }
}
