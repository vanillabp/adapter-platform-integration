package io.vanillabp.adapter.dummy.springboot.deployment;

import java.io.InputStream;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.intergration.adapter.spi.AdapterDeploymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DeploymentService implements AdapterDeploymentService<Object, Object, Object> {

  private final String adapterId;

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

    return DummyAdapterConfiguration.ADAPTER_TYPE;

  }

  @Override
  public List<Map.Entry<String, Object>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws IllegalFormatException {

    log.info("Dummy-Adapter: Reading BPMN for {}", workflowModuleId);

    return List.of(Map.entry("DummyProcess", new Object()));

  }

  @Override
  public Object prepareBpmn(
      final String workflowModuleId,
      final Object existingContext,
      final String filename,
      final String bpmnProcessId,
      final Object model) {

    log.info("Dummy-Adapter: Preparing BPMN for {}", workflowModuleId);


    return new Object();

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    log.info("Dummy-Adapter: Wiring BPMN for {}", workflowModuleId);

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    log.info("Dummy-Adapter: Deploying resources for {}", workflowModuleId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Adapter: Starting workflow processing for {}", workflowModuleId);

  }

}
