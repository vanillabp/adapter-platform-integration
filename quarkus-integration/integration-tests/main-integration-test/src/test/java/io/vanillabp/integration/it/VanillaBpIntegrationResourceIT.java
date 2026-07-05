package io.vanillabp.integration.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@QuarkusIntegrationTest
@ExtendWith(SuppressOutputExtension.class)
public class VanillaBpIntegrationResourceIT {

  @Test
  public void testHelloEndpoint() {
    given()
        .when().get("/vanillabp-integration")
        .then()
        .statusCode(200)
        .body(is("Hello vanillabp-integration"));
  }

}
