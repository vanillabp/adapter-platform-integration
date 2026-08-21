package io.vanillabp.integration.it.properties;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;

import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.ProdBuildResults;
import io.quarkus.test.ProdModeTestResults;
import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.FreePortUtil;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 101: a file of a workflow module ships defaults. The application wins
 * over it, whichever of its own files carries the value.
 *
 * <p>Three sources of the same key meet here: <i>test-module.yaml</i> inside the
 * workflow module's jar, <i>application.yaml</i> on the application's classpath
 * and <i>config/application.properties</i> next to the runner, which this test
 * writes into the working directory of the application before starting it.
 * The Spring Boot counterpart is the class of the same name there.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleConfigurationIsADefaultTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.properties")
          .addAsResource("application.yaml")
          .addPackage("io.vanillabp.integration.test"))
      // JVM args needed for tracking coverage. Check pom.xml for systemPropertyVariables
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      // started by this class once the external configuration file is in place
      .setRun(false)
      .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(FreePortUtil.getFreePort())));

  private static boolean started;

  @ProdBuildResults
  ProdModeTestResults prodModeTestResults;

  /**
   * Puts a configuration file next to the runner and starts the application.
   * Quarkus reads <code>config/application.properties</code> of the working
   * directory, and the runner's working directory is the one holding the jar
   * built for this test.
   *
   * @throws Exception Thrown if the file cannot be written
   */
  @BeforeEach
  public void startWithAConfigurationFileNextToTheRunner() throws Exception {

    if (started) {
      return;
    }

    final var runnerDirectory = prodModeTestResults
        .getBuiltArtifactPath()
        .getParent();
    final var configDirectory = Files.createDirectories(runnerDirectory.resolve("config"));
    Files.writeString(
        configDirectory.resolve("application.properties"),
        "test-module.overridden-from-external-file=from-the-file-next-to-the-runner\n");

    prodModeTest.start();
    started = true;

  }

  @Test
  public void testTheApplicationWinsOverTheWorkflowModule() {

    @SuppressWarnings("unchecked")
    final Map<String, String> properties = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/precedence-properties")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    // test-module.yaml writes ${urls.external-system}, application.yaml sets it
    Assertions.assertEquals("https://es.com", properties.get("external-system-url"));
    Assertions.assertEquals("https://es.com", properties.get("injected-external-system-url"));

    // the application's classpath application.yaml overrides what test-module.yaml shipped
    Assertions.assertEquals(
        "from-the-applications-classpath",
        properties.get("overridden-from-classpath"));
    Assertions.assertEquals(
        "from-the-applications-classpath",
        properties.get("injected-overridden-from-classpath"));

    // and the file next to the runner overrides both
    Assertions.assertEquals(
        "from-the-file-next-to-the-runner",
        properties.get("overridden-from-external-file"));
    Assertions.assertEquals(
        "from-the-file-next-to-the-runner",
        properties.get("injected-overridden-from-external-file"));

  }

  @Test
  public void testAValueOnlyTheModuleShipsIsStillVisible() {

    @SuppressWarnings("unchecked")
    final Map<String, Integer> properties = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/unmodified-properties")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    Assertions.assertEquals(8, properties.get("test-module"));

  }

}
