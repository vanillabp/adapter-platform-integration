package io.vanillabp.integration.test;

import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.test.multibpmn.Aggregate1;
import io.vanillabp.integration.test.multibpmn.Aggregate2;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import lombok.extern.slf4j.Slf4j;

@Path("/introspect")
@ApplicationScoped
@Slf4j
public class MultipleWorkflowServicesTestIIntrospectionController {

  @Inject
  ProcessService<Aggregate> processService;

  @Inject
  ProcessService<Aggregate1> processService1;

  @Inject
  ProcessService<Aggregate2> processService2;

  @ConfigProperty(name = "test-module.test", defaultValue = "-1")
  int testModuleProperty;

  @ConfigProperty(name = "test-module.test2", defaultValue = "-1")
  int testModuleProperty2;

  @ConfigProperty(name = "test-module.test-unmodified", defaultValue = "-1")
  int testModuleUnmodifiedProperty;

  @ConfigProperty(name = "multi-bpmn-module.test", defaultValue = "-1")
  int multiBpmnModuleProperty;

  @ConfigProperty(name = "multi-bpmn-module.test2", defaultValue = "-1")
  int multiBpmnModuleProperty2;

  @ConfigProperty(name = "multi-bpmn-module.test-unmodified", defaultValue = "-1")
  int multiBpmnModuleUnmodifiedProperty;

  @ConfigProperty(name = "no-module.test", defaultValue = "-1")
  int noModuleProperty;

  @ConfigProperty(name = "no-module.test2", defaultValue = "-1")
  int noModuleProperty2;

  @ConfigProperty(name = "no-module.test-unmodified", defaultValue = "-1")
  int noModuleUnmodifiedProperty;

  @GET
  @Path("/workflow-modules")
  public Map<String, String> getWorkflowModules() {

    return Map.of(
        "processService", processService.getWorkflowModuleId(),
        "processService1", processService1.getWorkflowModuleId(),
        "processService2", processService2.getWorkflowModuleId());

  }

  @GET
  @Path("/test-properties")
  public Map<String, Integer> getTestProperties() {

    return Map.of(
        "test-module", testModuleProperty,
        "multi-bpmn-module", multiBpmnModuleProperty,
        "no-module", noModuleProperty);

  }

  @GET
  @Path("/test2-properties")
  public Map<String, Integer> getTest2Properties() {

    return Map.of(
        "test-module", testModuleProperty2,
        "multi-bpmn-module", multiBpmnModuleProperty2,
        "no-module", noModuleProperty2);

  }

  @GET
  @Path("/unmodified-properties")
  public Map<String, Integer> getUnmodifiedTestProperties() {

    SmallRyeConfig config = (SmallRyeConfig) ConfigProvider.getConfig();
    config.getConfigSources().forEach(cs -> System.out.println(cs.getName()
        + " -> keys: "
        + cs.getPropertyNames())
    );

    return Map.of(
        "test-module", testModuleUnmodifiedProperty,
        "multi-bpmn-module", multiBpmnModuleUnmodifiedProperty,
        "no-module", noModuleUnmodifiedProperty);

  }

}
