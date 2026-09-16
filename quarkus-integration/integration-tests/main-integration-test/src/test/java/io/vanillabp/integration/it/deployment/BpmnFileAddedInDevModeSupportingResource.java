package io.vanillabp.integration.it.deployment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Records which BPMN files the dummy adapter was handed while the application booted
 * and hands them back over HTTP, so a dev-mode test can ask a restarted application
 * what it deployed. A restart builds this bean anew, which is also what the boot ID
 * says: it is drawn once per run, so two runs never answer the same.
 */
@ApplicationScoped
@Path("/")
public class BpmnFileAddedInDevModeSupportingResource implements DummyDeploymentListener {

  private final String bootId = UUID.randomUUID().toString();

  private final List<String> bpmnFiles = new CopyOnWriteArrayList<>();

  @Override
  public void onPipelineCall(
      final String adapterId,
      final String method,
      final String workflowModuleId,
      final String detail) {

    if ("readBpmn".equals(method)) {
      bpmnFiles.add(detail);
    }

  }

  @Path("deployed-bpmn")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getDeployedBpmnFiles() {

    return bpmnFiles
        .stream()
        .sorted()
        .collect(Collectors.joining(","));

  }

  @Path("boot-id")
  @GET
  @Produces(MediaType.TEXT_PLAIN)
  public String getBootId() {

    return bootId;

  }

}
