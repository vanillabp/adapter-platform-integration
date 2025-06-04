package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessServiceCdiBean<A> extends MigrationProcessService<A> {

  public ProcessServiceCdiBean(
      final Class<A> workflowAggregateClass) {

    super(workflowAggregateClass);

  }

}
