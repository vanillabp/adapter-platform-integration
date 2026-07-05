package io.vanillabp.extension.dummy.springboot.wiring;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DummyWiringService implements ExtensionWiringService<Object, Object> {

  private final MigrationAdapterProperties properties;

  @Override
  public Class<Object> getModelType() {

    return Object.class;

  }

  @Override
  public Class<Object> getProcessContextType() {

    return Object.class;

  }

  @Override
  public int getOrder() {

    return 0;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    log.info("Dummy-Extension: Wiring BPMN for {}", workflowModuleId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Extension: Starting workflow processing for {}", workflowModuleId);

  }

}
