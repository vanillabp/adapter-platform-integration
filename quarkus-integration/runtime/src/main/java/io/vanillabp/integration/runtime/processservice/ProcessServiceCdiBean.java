package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.spi.process.ProcessService;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link ProcessService} bean aware of adapter migration.
 */
@Slf4j
public class ProcessServiceCdiBean<A> extends MigrationProcessService<A> {

  /**
   * Wrapper for
   * {@link MigrationProcessService#MigrationProcessService(Class, MigrationAdapterProperties, List)}.
   *
   * @param workflowAggregateClass The workflow's aggregate class
   * @param properties Properties needed to handle migration between adapters
   */
  public ProcessServiceCdiBean(
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final List<MigratableProcessService<A>> processServices) {

    super(workflowAggregateClass, properties, processServices);

  }

}
