package io.vanillabp.integration.test;

import java.util.Map;

import io.vanillabp.integration.test.multibpmn.Aggregate1;
import io.vanillabp.integration.test.multibpmn.Aggregate2;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/introspect")
@ApplicationScoped
public class MultipleWorkflowServicesTestIIntrospectionController {

  @Inject
  ProcessService<Aggregate> processService;

  @Inject
  ProcessService<Aggregate1> processService1;

  @Inject
  ProcessService<Aggregate2> processService2;

  @GET
  @Path("/workflow-modules")
  public Map<String, String> getWorkflowModules() {

    return Map.of(
        "processService", processService.getWorkflowModuleId(),
        "processService1", processService1.getWorkflowModuleId(),
        "processService2", processService2.getWorkflowModuleId());

  }

}
