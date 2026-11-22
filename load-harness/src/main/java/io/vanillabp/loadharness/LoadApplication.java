package io.vanillabp.loadharness;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.spi.process.ProcessService;

/**
 * What the harness needs from the application it drives. Each platform implements it once,
 * in a few lines: Spring Boot with its transaction template, Quarkus with its transaction
 * API. Everything else of the run is the same on both platforms.
 */
public interface LoadApplication {

  /**
   * Runs the given work in a transaction of the application, the way an application calls
   * VanillaBP.
   *
   * @param <T> What the work returns
   * @param work The work
   * @return What the work returned
   */
  <T> T inTransaction(
      Supplier<T> work);

  /**
   * The process service of the load process.
   *
   * @return The process service
   */
  ProcessService<LoadAggregate> processService();

  /**
   * The double which plays the BPMS. The driver calls in through it, as a BPMS delivers a
   * task.
   *
   * @return The deployment service of the double
   */
  DummyDeploymentService bpms();

  /**
   * The workflow module of the load process.
   *
   * @return The id of the workflow module
   */
  String workflowModuleId();

  /**
   * The registry which holds the meters of VanillaBP.
   *
   * @return The meter registry
   */
  MeterRegistry meters();

  /**
   * The connection pool of the application, for the connections in use and the threads
   * which wait for one.
   *
   * @return The pool
   */
  ConnectionPoolProbe connectionPool();

}
