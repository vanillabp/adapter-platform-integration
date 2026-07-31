package io.vanillabp.integration.test.adapter;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;

/**
 * Minimal adapter deployment service for the platform's own Quarkus tests: the
 * runtime deployment pipeline requires one deployment service per prioritized
 * adapter id (like on Spring Boot). It does not parse BPMN and deploys nowhere;
 * {@code stopWorkflowProcessing} records the workflow module ids in a system
 * property so shutdown tests can verify the reverse pass across the test's
 * classloaders.
 */
public class TestAdapterDeploymentService implements AdapterDeploymentService<Object, Object> {

  /**
   * The system property receiving the workflow module IDs passed to
   * {@link #stopWorkflowProcessing(String, Object)}.
   */
  // deliberately OUTSIDE the vanillabp.* tree: with the blanket withMappingIgnore
  // gone, unknown keys under vanillabp.* fail the startup (typo detection)
  public static final String PROPERTY_STOPPED_MODULES = "vanillabp-test.stopped-modules";

  private final String adapterId;

  private final String adapterType;

  public TestAdapterDeploymentService(
      final String adapterId,
      final String adapterType) {

    this.adapterId = adapterId;
    this.adapterType = adapterType;

  }

  @Override
  public Class<Object> getModelType() {

    return Object.class;

  }

  @Override
  public Class<Object> getProcessContextType() {

    return Object.class;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return adapterType;

  }

  @Override
  public List<Map.Entry<String, Object>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    return List.of(Map.entry(filename, new Object()));

  }

  @Override
  public Object prepareBpmn(
      final String workflowModuleId,
      final Object existingContext,
      final String filename,
      final String bpmnProcessId,
      final Object model) {

    return existingContext != null
        ? existingContext
        : new Object();

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    // nothing to wire in the platform's own tests

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    // deploys nowhere - the platform tests only need the pipeline mechanics

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    // nothing to start

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    final var stoppedSoFar = System.getProperty(PROPERTY_STOPPED_MODULES);
    System.setProperty(
        PROPERTY_STOPPED_MODULES,
        (stoppedSoFar == null) || stoppedSoFar.isBlank()
            ? workflowModuleId
            : stoppedSoFar
                + ","
                + workflowModuleId);

  }

}
