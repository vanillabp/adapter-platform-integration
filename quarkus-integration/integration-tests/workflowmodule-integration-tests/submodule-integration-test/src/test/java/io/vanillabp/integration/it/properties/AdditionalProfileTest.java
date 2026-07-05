package io.vanillabp.integration.it.properties;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.FreePortUtil;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Validate properties read from application and workflow module properties
 * respecting additional profiles.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AdditionalProfileTest {

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
      .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(FreePortUtil.getFreePort()),
          "quarkus.profile", "prod,testprofile"));

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
            "test-module", 21, // loaded from test-module-testprofile.yaml
            "multi-bpmn-module", 121, // loaded from multi-bpmn-module-testprofile.yaml
            "no-module", 52), // loaded from test-module-testprofile.yaml overriding application.yaml
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
            "test-module", 4720, // loaded from test-module-testprofile.properties
            "multi-bpmn-module", 47120, // loaded from multi-bpmn-module-testprofile.properties
            "no-module", 4746), // loaded from application-testprofile.yaml
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