package io.vanillabp.integration.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.quarkusProdModeTestDefaults;
import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static org.hamcrest.Matchers.is;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.NoIndexModuleIntrospectionController;
import io.vanillabp.integration.test.utils.FreePortUtil;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Proves that a workflow-module JAR NOT having a Jandex index (containing only the
 * <code>META-INF/workflow-module</code> descriptor, its configuration file and BPMN
 * resources) is detected as a workflow module: the workflow-module descriptor is
 * registered as an additional application-archive marker by the VanillaBP extension.
 * If the module was not detected, the config source for
 * <code>no-index-module.yaml</code> would not be generated: the application would not
 * even build since the workflow module's configuration
 * (<code>vanillabp.workflow-modules.no-index-module</code>) is part of that file, and
 * the property <code>no-index-module.test</code> asserted by this test would fall back
 * to its default value.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleWithoutJandexIndexTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(NoIndexModuleIntrospectionController.class))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(FreePortUtil.getFreePort())));

  @Test
  public void testWorkflowModuleWithoutJandexIndexIsDetected() {

    RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/no-index-module-test-property")
        .then()
        .statusCode(200)
        .body(is("42"));

  }

}
