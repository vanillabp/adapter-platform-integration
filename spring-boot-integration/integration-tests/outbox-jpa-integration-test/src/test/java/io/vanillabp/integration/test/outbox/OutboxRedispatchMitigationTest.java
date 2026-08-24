package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The START re-dispatch mitigation: a recovered/retried START outbox
 * entry (attempts &gt; 0) probes {@code awarenessOfWorkflowForRedispatch} on the
 * recorded adapter BEFORE re-dispatching - a workflow already known there means
 * the previous dispatch already started it, so the entry is consumed WITHOUT a
 * second start. This test proves the MITIGATION, not a closed window: the residual
 * at-least-once window (a hard crash between the remote call and recording the
 * completion) is an accepted eventual-consistency property.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxRedispatchMitigationTest {

  // one database PER TEST (see OutboxRecoveryTest for the reasoning)
  private static final String DATASOURCE_URL_PATTERN = "jdbc:h2:mem:outbox-mitigation-%s;DB_CLOSE_DELAY=-1";

  private ConfigurableApplicationContext runApplication(
      final String database,
      final String pollInterval) {

    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url="
                + DATASOURCE_URL_PATTERN.formatted(database),
            "--vanillabp.outbox.poll-interval="
                + pollInterval,
            "--vanillabp.outbox.attempt-frequency=PT0.5S");

  }

  @Test
  @DisplayName("A retried START entry whose workflow is already known is consumed without a second start")
  public void retriedStartEntryDoesNotStartASecondWorkflow(
      final CapturedOutput output) throws Exception {

    // the restarted context reports the workflow as ALREADY KNOWN - like a crash
    // right after a successful CreateProcessInstance whose engine state is
    // visible by the time the entry is retried
    SteerableTaskAwarenessSource.initialAnswer = WorkflowAwareness.ACTIVE;
    try {

      // first context: the dispatch fails (BPMS "unreachable") - gruelbox
      // persists the failed attempt, the entry stays pending with attempts > 0
      try (var context = runApplication("redispatch", "PT1H")) {
        final var listener = context.getBean(RecordingPhaseTwoListener.class);
        listener.failNextDispatches(Integer.MAX_VALUE);
        @SuppressWarnings("unchecked")
        final var processService = (ProcessService<Aggregate>) context
            .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
            .getObject();
        final var transactionTemplate = context.getBean(TransactionTemplate.class);
        final var attachedAggregate = transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("redispatch-mitigation");
          return processService.startWorkflow(aggregate);
        });
        assertNotNull(attachedAggregate);
        listener.awaitInvocations(1, 10000);
        // give the outbox time to persist the failed attempt before "crashing"
        Thread.sleep(1000);
      }

      // second context: the recovery poll picks the entry up; the mitigation
      // probe answers ACTIVE - the entry is consumed, phase two NEVER reaches
      // the adapter
      try (var context = runApplication("redispatch", "PT0.5S")) {
        final var listener = context.getBean(RecordingPhaseTwoListener.class);

        final var deadline = System.currentTimeMillis() + 15000;
        while ((System.currentTimeMillis() < deadline) && !output.getAll()
            .contains("Skipped re-dispatched phase two of starting the workflow")) {
          Thread.sleep(100);
        }

        assertTrue(
            output.getAll().contains("Skipped re-dispatched phase two of starting the workflow"),
            "expected the mitigation to skip the re-dispatched start but got: "
                + output.getAll());
        assertTrue(
            listener.getInvocations().isEmpty(),
            "the adapter's phase two must never run for the mitigated entry but got: "
                + listener.getInvocations());
      }

    } finally {
      SteerableTaskAwarenessSource.initialAnswer = WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

}
