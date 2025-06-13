package io.vanillabp.integration.runtime.processservice;

import java.util.function.Supplier;

import io.quarkus.runtime.annotations.Recorder;
import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.spi.process.ProcessService;

/**
 * Builds {@link ProcessService} beans aware of adapter migration
 * for {@link io.vanillabp.spi.service.WorkflowService}
 * annotated beans.
 */
@Recorder
public class ProcessServiceCdiBeanRecorder {

  /**
   * Supplier for {@link ProcessService} beans aware of adapter migration
   * for {@link io.vanillabp.spi.service.WorkflowService}
   * annotated beans.
   * 
   * @param workflowAggregateClass The workflow's aggregate class
   * @param properties Properties needed to handle migration between adapters
   * @return The {@link ProcessService} bean
   */
  public Supplier<ProcessService<?>> processServiceSupplier(
      final Class<?> workflowAggregateClass,
      final MigrationAdapterProperties properties) {

    return () -> new ProcessServiceCdiBean<>(workflowAggregateClass, properties);

  }

}
