package io.vanillabp.integration.it;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.intergration.test.utils.FreePortUtil;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class MultipleWorkflowServicesTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addPackage("io.vanillabp.integration.test"))
      // JVM args needed for tracking coverage. Check pom.xml for systemPropertyVariables
      .setJVMArgs(List.of(System.getProperty("jacoco.agent"))) // needed for tracking coverage
      .setRun(true)
      .setRuntimeProperties(Map.of("quarkus.http.port", Integer.toString(FreePortUtil.getFreePort())));

  @Test
  public void testProcessServicesBelongToTheRightWorkflowModule() {

    @SuppressWarnings("unchecked")
    Map<String, String> workflowModules = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(FreePortUtil.getFreePort())
        .get("introspect/workflow-modules")
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

    Assertions.assertEquals(
        Map.of(
            "processService", "test-module",
            "processService1", "multi-bpmn-module",
            "processService2", "multi-bpmn-module"),
        workflowModules);

  }

}