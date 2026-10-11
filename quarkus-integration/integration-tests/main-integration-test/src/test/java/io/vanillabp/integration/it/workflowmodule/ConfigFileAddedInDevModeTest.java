package io.vanillabp.integration.it.workflowmodule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module may bring its own configuration file. Adding one to a module is as
 * ordinary as adding a process to it. The application here starts with a module which has
 * none. The first test adds, one after the other, every file which the module's config
 * sources read: the file at the classpath root, the file for the active profile, and the
 * file in the subdirectory named after the module.
 * <p>
 * A developer meets this first in dev mode. An application which goes on running with the
 * files of the last build does not tell the developer. The other two tests guard what the
 * fix must not break. A YAML file named after no workflow module still restarts nothing.
 * A configuration file which is there and changes still restarts the application. That
 * restart carries the changed value into the running application.
 *
 * @see ConfigWatcherInDevModeTest
 * @see ConfigWatcherForSubdirectoryConfigInDevModeTest
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConfigFileAddedInDevModeTest {

  private static final String ROOT_CONFIG = "test-module.yaml";

  private static final String PROFILE_CONFIG = "test-module-dev.yaml";

  private static final String SUBDIRECTORY_CONFIG = "test-module/test-module.yml";

  private static final String PROPERTIES_CONFIG = "test-module.properties";

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
              + "\n"
              // the BPMS double names the process after its file, and no class here claims it
              + "vanillabp.workflow-modules.test-module.workflows.dummy.implemented-externally=true\n"),
              "application.properties")
          .addAsResource("META-INF/workflow-module")
          // the resources location of the module. It also creates the subdirectory which
          // one of the configuration files below is written into
          .add(new StringAsset("not parsed by the dummy adapter"), "test-module/processes/dummy/dummy.bpmn")
          .addClass(ConfigFileAddedInDevModeSupportingResource.class));

  private static String get(
      final String path) {

    return RestAssured
        .given()
        .port(PORT)
        .when()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  private static String theFileWasNotRead(
      final String path,
      final String bootIdBefore) {

    return "the configuration file added while the application ran was not read, the boot ID before was "
        + bootIdBefore
        + " and is "
        + get("/boot-id")
        + " now, so the value of "
        + path
        + " is";

  }

  @Test
  @DisplayName("Every configuration file of a workflow module is read when it appears")
  public void aConfigFileAddedAfterTheFirstBuildIsRead() {

    assertEquals("absent", get("/added-at-root"));

    // the file at the classpath root, in a module which had none at all
    var bootIdBefore = get("/boot-id");
    test.addResourceFile(
        ROOT_CONFIG,
        "test-module:\n  added-at-root: root\n  added-for-profile: without the profile\n");
    assertEquals(
        "root",
        get("/added-at-root"),
        theFileWasNotRead(ROOT_CONFIG, bootIdBefore));

    // the file for the profile which dev mode runs under, next to the one at the root.
    // SmallRye reads a profile file only where the file it belongs to is there too
    assertEquals("without the profile", get("/added-for-profile"));
    bootIdBefore = get("/boot-id");
    test.addResourceFile(
        PROFILE_CONFIG,
        "test-module:\n  added-for-profile: dev\n");
    assertEquals(
        "dev",
        get("/added-for-profile"),
        theFileWasNotRead(PROFILE_CONFIG, bootIdBefore));

    // the file in the subdirectory named after the module. A module packaged as its own
    // Maven module puts it there to stay clear of the classpath root. This time it is a
    // '.yml', because the same file name may lie in one place only
    bootIdBefore = get("/boot-id");
    test.addResourceFile(
        SUBDIRECTORY_CONFIG,
        "test-module:\n  added-in-subdirectory: subdirectory\n");
    assertEquals(
        "subdirectory",
        get("/added-in-subdirectory"),
        theFileWasNotRead(SUBDIRECTORY_CONFIG, bootIdBefore));

  }

  @Test
  @DisplayName("A file which belongs to no workflow module restarts nothing")
  public void aFileWhichBelongsToNoWorkflowModuleRestartsNothing() {

    final var bootIdBefore = get("/boot-id");

    test.addResourceFile("something-else.yaml", "something-else: 1\n");

    assertEquals(
        bootIdBefore,
        get("/boot-id"),
        "a YAML file named after no workflow module restarted the application");

  }

  @Test
  @DisplayName("A changed configuration file still restarts the application")
  public void aChangedConfigFileStillRestartsTheApplication() {

    // a file of its own, so this test says the same whatever ran before it
    test.addResourceFile(PROPERTIES_CONFIG, "test-module.changed=before\n");
    assertEquals("before", get("/changed"));

    test.modifyResourceFile(PROPERTIES_CONFIG, content -> content
        .replace("before", "after"));

    assertEquals(
        "after",
        get("/changed"),
        "changing a configuration file of the workflow module did not restart the application");

  }

}
