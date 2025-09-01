package io.vanillabp.integration.it.workflowmodule;

import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class ConfigWatcherInDevModeIT {

  @RegisterExtension
  static final QuarkusDevModeTest test = new QuarkusDevModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("test-module.yaml")
          .addAsResource("META-INF/workflow-module")
          .addClass(ConfigWatcherInDevModeTestSupportingResource.class));

  @Test
  public void testConfigReload() {

    RestAssured
        .when()
        .get("/test")
        .then()
        .statusCode(200)
        .body(is("1"));

    test.modifyResourceFile(
        "test-module.yaml",
        s -> s.replace("1", "2"));

    RestAssured
        .when()
        .get("/test")
        .then()
        .statusCode(200)
        .body(is("2"));

  }

}
