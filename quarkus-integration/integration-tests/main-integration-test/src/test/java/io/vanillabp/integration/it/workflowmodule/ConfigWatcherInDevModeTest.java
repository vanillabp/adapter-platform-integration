package io.vanillabp.integration.it.workflowmodule;

import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module's configuration file at the classpath root is watched in dev mode:
 * changing it restarts the application, and the running application then answers with the
 * changed value.
 * <p>
 * The class was called <code>ConfigWatcherInDevModeIT</code> until 2026-09-18 and ran in
 * no build for it. A <code>QuarkusDevModeTest</code> needs no packaged application, so it
 * belongs to Surefire like the two tests next to it, and the name is what puts it there.
 *
 * @see ConfigFileAddedInDevModeTest
 * @see ConfigWatcherForSubdirectoryConfigInDevModeTest
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConfigWatcherInDevModeTest {

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
