package io.vanillabp.integration.it.properties;

import static io.vanillabp.intergration.test.utils.TestCoverageUtils.quarkusProdModeTestDefaults;
import static io.vanillabp.intergration.test.utils.TestCoverageUtils.testCoverageJavaAgent;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.intergration.test.utils.FreePortUtil;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

/**
 * Validate properties read from application and workflow module properties
 * with no additional profiles.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationPropertiesAndYamlTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.properties")
          .addAsResource("application.yaml")
          .addAsResource("application-testprofile.properties")
          .addAsResource("application-testprofile.yaml")
          .addPackage("io.vanillabp.integration.test"))
      // JVM args needed for tracking coverage. Check pom.xml for systemPropertyVariables
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(FreePortUtil.getFreePort())));

  @Test
  public void testProperties() {

    @SuppressWarnings("unchecked")
    Map<String, Integer> workflowModules = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/test-properties")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    Assertions.assertEquals(
        Map.of(
            "test-module", 11, // loaded from test-module.yaml
            "multi-bpmn-module", 101, // loaded from multi-bpmn-module.yaml
            "no-module", 48), // loaded from test-module.yaml overriding application.yaml
        workflowModules);

  }

  @Test
  public void testProperties2() {

    @SuppressWarnings("unchecked")
    Map<String, Integer> workflowModules = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/test2-properties")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    Assertions.assertEquals(
        Map.of(
            "test-module", 4710, // loaded from test-module.properties
            "multi-bpmn-module", 47100, // loaded from multi-bpmn-module.properties
            "no-module", 4748), // loaded from application.properties
        workflowModules);

  }

  @Test
  public void testUnmodifiedProperties() {

    @SuppressWarnings("unchecked")
    Map<String, Integer> workflowModules = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/unmodified-properties")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    Assertions.assertEquals(
        Map.of(
            "test-module", 8, // loaded from test-module.yaml
            "multi-bpmn-module", 9, // loaded from multi-bpmn-module.yaml
            "no-module", 7), // loaded from application.yaml
        workflowModules);

  }

}