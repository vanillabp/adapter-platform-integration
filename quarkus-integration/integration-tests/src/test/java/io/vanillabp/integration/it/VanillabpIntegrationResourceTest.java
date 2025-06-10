package io.vanillabp.integration.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class VanillabpIntegrationResourceTest {

  @Test
  public void testHelloEndpoint() {
    given()
        .when().get("/vanillabp-integration")
        .then()
        .statusCode(200)
        .body(is("Hello vanillabp-integration"));
  }
}
