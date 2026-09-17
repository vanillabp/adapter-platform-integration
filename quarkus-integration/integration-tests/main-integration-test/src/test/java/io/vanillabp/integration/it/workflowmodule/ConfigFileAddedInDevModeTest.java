package io.vanillabp.integration.it.workflowmodule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module may bring its own configuration file, and adding one to a module is
 * as ordinary as adding a process to it. The application here starts with a module which
 * has none, and the first test adds, one after the other, every file the module's config
 * sources read: the file at the classpath root, the file for the active profile, and the
 * file in the subdirectory named after the module.
 * <p>
 * Dev mode is where a developer meets this first, and an application which goes on
 * running against the files of the last build says nothing about it. The other two tests
 * keep what that must not cost. A YAML file named after no workflow module still restarts
 * nothing, and a configuration file which is there and changes still restarts the
 * application, which is what carries the changed value into the running one.
 *
 * @see ConfigWatcherInDevModeIT
 * @see ConfigWatcherForSubdirectoryConfigInDevModeTest
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConfigFileAddedInDevModeTest {

  private static final String ROOT_CONFIG = "test-module.yaml";

  private static final String PROFILE_CONFIG = "test-module-dev.yaml";

  private static final String SUBDIRECTORY_CONFIG = "test-module/test-module.yml";

  private static final String PROPERTIES_CONFIG = "test-module.properties";

  @RegisterExtension
  static final QuarkusDevModeTest test = new QuarkusDevModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module")
          // the resources location of the module, which is also what creates the
          // subdirectory one of the configuration files below is written into
          .add(new StringAsset("not parsed by the dummy adapter"), "test-module/processes/dummy/dummy.bpmn")
          .addClass(ConfigFileAddedInDevModeSupportingResource.class));

  private static String get(
      final String path) {

    return RestAssured
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

    // the file for the profile dev mode runs under, next to the one at the root:
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

    // the file in the subdirectory named after the module, where a module packaged as
    // its own Maven module puts it to stay clear of the classpath root; a '.yml' this
    // time, because the same file name may lie in one place only
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
