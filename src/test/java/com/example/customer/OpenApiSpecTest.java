package com.example.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Writes the OpenAPI document that customer-service publishes to PactFlow as its provider contract.
 *
 * <p>The document is generated from the running application, so it cannot drift from the controller
 * the way a hand-maintained YAML file would. The output path is set by the {@code openApiSpecTest}
 * Gradle task; the default keeps a plain {@code ./gradlew test} run working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSpecTest {

  @LocalServerPort
  int port;

  @Test
  void writesTheOpenApiDocument() throws Exception {
    HttpResponse<String> response = HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"openapi\"").contains("\"/customers\"");

    Path output = Path.of(System.getProperty("openapi.output", "build/openapi/openapi.json"));
    Files.createDirectories(output.getParent());
    Files.writeString(output, response.body());
  }
}
