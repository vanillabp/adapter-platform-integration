package io.vanillabp.integration.runtime.processservice;


import java.util.List;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.spi.process.ProcessService;

/**
 * Builds {@link ProcessService} beans aware of adapter migration
 * for {@link io.vanillabp.spi.service.WorkflowService}
 * annotated beans.
 */
@Recorder
@SuppressWarnings({
    "rawtypes", "unchecked"
})
public class ProcessServiceCdiBeanRecorder {

  /**
   * Supplier for {@link ProcessService} beans aware of adapter migration
   * for {@link io.vanillabp.spi.service.WorkflowService}
   * annotated beans.
   *
   * @param workflowModuleId The workflows module ID
   * @param bpmnProcessId The workflows BPMN process ID
   * @param workflowAggregateClassName The workflows aggregate class
   * @param properties Properties needed to handle migration between adapters
   * @return The {@link ProcessService} bean
   */
  public RuntimeValue<ProcessServiceCdiBean> recordProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateClassName,
      final MigrationAdapterProperties properties) throws ClassNotFoundException {

    final var workflowAggregateClass = Thread
        .currentThread()
        .getContextClassLoader()
        .loadClass(workflowAggregateClassName);
    final var service = new ProcessServiceCdiBean(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, List.of());
    return new RuntimeValue<>(service);

  }

  public void startProcessService(
      final ShutdownContext shutdownContext,
      final RuntimeValue<? extends ProcessServiceCdiBean> processService) {

    final var service = processService.getValue();
    service.startService();
    shutdownContext.addShutdownTask(service::stopService);

  }

}
