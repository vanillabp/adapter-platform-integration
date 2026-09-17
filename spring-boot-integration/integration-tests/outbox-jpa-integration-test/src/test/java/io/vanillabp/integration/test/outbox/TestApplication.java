package io.vanillabp.integration.test.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test application for the gruelbox-based JPA {@link io.vanillabp.integration.spi.PhaseTwoOutbox}:
 * the dummy adapter is forced to require a two-phase commit for starting workflows
 * (property <code>dummy-adapter.at-least-once-delivery</code>) and a
 * {@link RecordingPhaseTwoListener} observes (and optionally fails) phase two.
 */
@SpringBootApplication
public class TestApplication {

  @Bean
  public RecordingPhaseTwoListener recordingPhaseTwoListener() {

    return new RecordingPhaseTwoListener();

  }

  /**
   * The tasks of the version-conflict acceptance test - the dummy adapter
   * has no model to read them from.
   *
   * @return The wiring of the BPMN process 'ConflictProcess'
   */
  @Bean
  public io.vanillabp.bpmsdouble.DummyTaskWiringSource conflictTaskWiringSource() {

    return (
        adapterId,
        workflowModuleId,
        bpmnProcessId) -> "ConflictProcess".equals(bpmnProcessId)
            ? java.util.List
                .of(
                    new io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec(
                        "Activity_Conflict", "conflictingTask"),
                    new io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec(
                        "Activity_Undisturbed", "undisturbedTask"))
            : java.util.List.of();

  }

  @Bean
  public SteerableTaskAwarenessSource steerableTaskAwarenessSource() {

    return new SteerableTaskAwarenessSource();

  }

  /**
   * Stands in for an extension contributing an operation of its own to the outbox.
   *
   * @param registry The core's operation registry
   * @param handlers The seam VanillaBP offers an extension to have a handler run
   * @return The sample extension
   */
  /**
   * The persistence of this scenario's aggregate: the application's own, because it
   * keeps a history of its aggregate the way an application using Hibernate Envers
   * does.
   *
   * @param repository The repository everything else is delegated to
   * @return The persistence VanillaBP resolves for the aggregate of this scenario
   */
  @Bean
  public AggregatePersistenceWithAHistory aggregatePersistence(
      final AggregateRepository repository) {

    return new AggregatePersistenceWithAHistory(repository);

  }

  /**
   * Stands in for the per-aggregate service an extension offers, and the only way an
   * extension reaches the persistence of an aggregate.
   *
   * @return The factory building one service per workflow aggregate
   */
  @Bean
  public AggregateHistoryServiceFactory aggregateHistoryServiceFactory() {

    return new AggregateHistoryServiceFactory();

  }

  @Bean
  public SampleExtension sampleExtension(
      final io.vanillabp.integration.spi.PhaseOperationRegistry registry,
      final io.vanillabp.integration.extension.spi.handler.ExtensionHandlers handlers) {

    return new SampleExtension(registry, handlers);

  }

}
