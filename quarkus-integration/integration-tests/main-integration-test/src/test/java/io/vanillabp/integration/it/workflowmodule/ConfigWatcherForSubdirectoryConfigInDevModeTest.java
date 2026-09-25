package io.vanillabp.integration.it.workflowmodule;

import static org.hamcrest.Matchers.is;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Like {@link ConfigWatcherInDevModeTest} but for a workflow-module-specific config file
 * placed in a subdirectory named after the workflow module ID
 * (<code>test-module/test-module.yaml</code>): modifying such a file has to trigger a
 * dev-mode reload as well, so the file has to be watched using its relative path, not
 * just its file name.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConfigWatcherForSubdirectoryConfigInDevModeTest {

  // Dev mode reads 'quarkus.http.port', not the 'quarkus.http.test-port' which Surefire
  // sets to zero, so this application needs a free port of its own. Without one it takes
  // the default 8080 and a second build on the machine answers the requests below.
  private static final int PORT = OneFreePortPerJvm.getPort();

  @RegisterExtension
  static final QuarkusDevModeTest test = new QuarkusDevModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .add(new StringAsset("quarkus.http.port="
              + PORT
              + "\n"), "application.properties")
          .add(new StringAsset("test-module:\n  subdir-test: 1\n"), "test-module/test-module.yaml")
          .addAsResource("META-INF/workflow-module")
          .addClass(ConfigWatcherForSubdirectoryConfigInDevModeTestSupportingResource.class));

  @Test
  public void testConfigReload() {

    RestAssured
        .given()
        .port(PORT)
        .when()
        .get("/subdir-test")
        .then()
        .statusCode(200)
        .body(is("1"));

    test.modifyResourceFile(
        "test-module/test-module.yaml",
        s -> s.replace("1", "2"));

    RestAssured
        .given()
        .port(PORT)
        .when()
        .get("/subdir-test")
        .then()
        .statusCode(200)
        .body(is("2"));

  }

}
