package io.vanillabp.integration.test.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Test application for the gruelbox-based JPA {@link io.vanillabp.integration.spi.PhaseTwoOutbox}:
 * the dummy adapter is forced to require a two-phase commit for starting workflows
 * (property <code>dummy-adapter.two-phase-commit</code>) and a
 * {@link RecordingPhaseTwoListener} observes (and optionally fails) phase two.
 */
@SpringBootApplication
public class TestApplication {

  @Bean
  public RecordingPhaseTwoListener recordingPhaseTwoListener() {

    return new RecordingPhaseTwoListener();

  }

  /**
   * The tasks of the version-conflict acceptance test (story 59) - the dummy adapter
   * has no model to read them from.
   *
   * @return The wiring of the BPMN process 'ConflictProcess'
   */
  @Bean
  public io.vanillabp.adapter.dummy.springboot.deployment.DummyTaskWiringSource conflictTaskWiringSource() {

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
   * @return The sample extension
   */
  @Bean
  public SampleExtension sampleExtension(
      final io.vanillabp.integration.spi.PhaseTwoOperationRegistry registry) {

    return new SampleExtension(registry);

  }

}
