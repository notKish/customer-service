package com.example.customer;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

/**
 * Metadata for the generated OpenAPI document. Without this, springdoc advertises the RANDOM_PORT
 * test server as the contract's only server.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI customerServiceOpenApi() {
    return new OpenAPI()
        .info(new Info().title("customer-service").version("v1"))
        .servers(List.of(new Server().url("/")));
  }
}
