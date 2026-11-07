package com.example.customer;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/customers")
class CustomerController {

  private static final UUID KNOWN_CUSTOMER_ID =
      UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

  @Operation(summary = "Get a customer by id")
  @ApiResponse(responseCode = "200", description = "Customer found",
      content = @Content(mediaType = "application/json",
          schema = @Schema(implementation = Customer.class)))
  @ApiResponse(responseCode = "404", description = "No customer with that id", content = @Content)
  @ApiResponse(responseCode = "400", description = "id is not a UUID", content = @Content)
  @GetMapping("/{id}")
  ResponseEntity<Customer> getCustomer(@PathVariable UUID id) {
    if (!KNOWN_CUSTOMER_ID.equals(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok(new Customer(id, "Alice Smith", "alice@example.com"));
  }

  /**
   * Query-parameter endpoint: the consumer pacts match {@code status}/{@code page}/{@code size}
   * with regular expressions and expect an {@code X-Request-Id} response header.
   */
  @Operation(summary = "List a page of customers")
  // Documented but not enforced: CustomerController ignores the header.
  @Parameters(@Parameter(name = "X-Api-Key", in = ParameterIn.HEADER, required = false,
      description = "Consumer API key", schema = @Schema(implementation = String.class)))
  @ApiResponse(responseCode = "200", description = "A page of customers",
      content = @Content(mediaType = "application/json",
          schema = @Schema(implementation = CustomerPage.class)),
      headers = @Header(name = "X-Request-Id", description = "Correlation id for the request",
          schema = @Schema(implementation = String.class)))
  @ApiResponse(responseCode = "400", description = "A query parameter has the wrong type",
      content = @Content)
  @GetMapping
  CustomerPage listCustomers(
      @RequestParam(defaultValue = "active") String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size,
      HttpServletResponse response) {
    response.setHeader("X-Request-Id", UUID.randomUUID().toString());
    List<Customer> items =
        List.of(new Customer(KNOWN_CUSTOMER_ID, "Alice Smith", "alice@example.com"));
    return new CustomerPage(items, page, size, items.size());
  }

  /**
   * POST endpoint: {@code name} is required (blank is rejected with 400), {@code email} and
   * {@code tags} are optional, which the consumer pacts express as "field absent" vs "field present".
   */
  @Operation(summary = "Create a customer")
  @ApiResponse(responseCode = "201", description = "Customer created",
      headers = @Header(name = "Location", description = "URI of the new customer",
          schema = @Schema(implementation = String.class)),
      content = @Content(mediaType = "application/json",
          schema = @Schema(implementation = Customer.class)))
  @ApiResponse(responseCode = "400", description = "name is missing or blank",
      content = @Content(mediaType = "application/json",
          schema = @Schema(implementation = ApiError.class)))
  @PostMapping
  ResponseEntity<?> createCustomer(@RequestBody CreateCustomerRequest request) {
    if (request.name() == null || request.name().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
    }
    UUID id = UUID.randomUUID();
    Customer customer = new Customer(id, request.name(), request.email());
    return ResponseEntity.created(URI.create("/customers/" + id)).body(customer);
  }

  record Customer(UUID id, String name, @Schema(nullable = true) String email) {}

  record CustomerPage(List<Customer> items, int page, int size, int total) {}

  record CreateCustomerRequest(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
      String email,
      List<String> tags) {}

  /** The error body shape; {@link #createCustomer} returns an equivalent map. */
  record ApiError(String error) {}
}
