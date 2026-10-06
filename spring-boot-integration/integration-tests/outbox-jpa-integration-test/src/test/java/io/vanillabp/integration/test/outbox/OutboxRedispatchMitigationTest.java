package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
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

      // first context: the dispatch fails (BPMS "unreachable") - the store writes the
      // failed attempt down, the entry stays OPEN with attempts > 0
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
        // the entry has to carry the failed attempt before the context goes away: the
        // mitigation reads that counter, so a context closed sooner would leave an entry
        // the second context recovers as a first dispatch
        FailedAttempts.awaitWrittenDown(context, 1);
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

  @Test
  @DisplayName("A retried second START of an aggregate does not take the first workflow for its own and starts")
  public void retriedSecondStartIsNotSkippedForTheFirstWorkflow(
      final CapturedOutput output) throws Exception {

    try (var context = runApplication("second-start", "PT0.5S")) {
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      listener.reset();
      @SuppressWarnings("unchecked")
      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();
      final var transactionTemplate = context.getBean(TransactionTemplate.class);
      final var outbox = PhaseTwoOutboxReader
          .ofTheVanillaBpOutbox(new TransactionAwareDataSourceProxy(context.getBean(DataSource.class)));

      // the first workflow of the aggregate: it starts, and its entry is done, which frees the
      // key the second start is planned under
      final var aggregate = transactionTemplate.execute(status -> {
        final var created = new Aggregate();
        created.setContent("first-workflow");
        return processService.startWorkflow(created);
      });
      assertNotNull(aggregate);
      listener.awaitInvocations(1, 15000);
      final var aggregateId = aggregate.getId().toString();
      final var deadline = System.currentTimeMillis() + 15000;
      while (!outbox
          .entries()
          .stream()
          .filter(entry -> aggregateId.equals(entry.aggregateId()))
          .allMatch(PhaseTwoOutboxReader.Entry::wasDispatched)) {
        assertTrue(System.currentTimeMillis() < deadline, "the first start was not marked done in time");
        Thread.sleep(50);
      }
      final var firstStart = outbox
          .entries()
          .stream()
          .filter(entry -> aggregateId.equals(entry.aggregateId()))
          .filter(entry -> PhaseOperation.START_WORKFLOW.name().equals(entry.operation()))
          .findFirst()
          .orElseThrow();

      // the row the first start leaves behind. The dummy adapter reports no workflow id, so the
      // row is written here, the way an adapter which reports one leaves it, a day before the
      // second start
      transactionTemplate.executeWithoutResult(status -> context
          .getBean(JdbcTaskDeliveryLog.class)
          .recordWorkflowStart(
              TaskDelivery
                  .workflowStart(
                      "test",
                      firstStart.workflowModuleId(),
                      firstStart.bpmnProcessId(),
                      aggregateId,
                      "first-workflow-of-"
                          + aggregateId,
                      null,
                      Instant.now().minus(Duration.ofDays(1)))));

      // the second workflow: its first dispatch fails before it creates anything
      listener.failNextDispatches(1);
      transactionTemplate.execute(status -> processService.startWorkflow(aggregate));

      // the retry reads the row of the first workflow, which is older than the entry, so it asks
      // the adapter about workflows started since, which knows none, and starts
      listener.awaitInvocations(3, 15000);
      assertEquals(
          3,
          listener.getInvocations().size(),
          "the first start, the failed attempt of the second one and its retry; got: "
              + listener.getInvocations());
      assertFalse(
          output.getAll().contains("Skipped re-dispatched phase two of starting the workflow"),
          "the second start was taken for the first workflow");
    }

  }

}
