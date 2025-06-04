package io.vanillabp.integration.runtime.processservice;

import java.util.function.Supplier;

import io.quarkus.runtime.annotations.Recorder;
import io.vanillabp.spi.process.ProcessService;

@Recorder
public class ProcessServiceCdiBeanRecorder {

  public Supplier<ProcessService<?>> processServiceSupplier(
      final Class<?> workflowAggregateClass) {

    return () -> new ProcessServiceCdiBean(workflowAggregateClass);

  }

}
