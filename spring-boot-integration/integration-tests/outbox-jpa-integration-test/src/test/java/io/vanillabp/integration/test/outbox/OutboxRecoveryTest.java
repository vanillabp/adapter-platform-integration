package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Simulates the crash recovery of the JDBC phase-two outbox on JPA. A first application
 * context leaves a committed entry in the database which was never processed. Its
 * dispatch fails, and the poll interval is too long for a retry, as with a crashed
 * instance. A second application context uses the same H2 database (in memory, kept
 * alive). It must dispatch the left-over entry at startup.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxRecoveryTest {

  // one database PER TEST. The kept-alive in-memory database outlives a test, but
  // Hibernate creates the aggregate table again at every context start. Reused aggregate
  // IDs would collide with the idempotency keys of the entries of an earlier test
  private static final String DATASOURCE_URL_PATTERN = "jdbc:h2:mem:outbox-recovery-%s;DB_CLOSE_DELAY=-1";

  /**
   * Runs the test application with its own H2 database, not shared with other test
   * contexts. The configuration goes in as command-line arguments, because those win
   * over <code>application.yaml</code>.
   *
   * @param pollInterval The poll interval to be used by the context
   * @return The running application context
   */
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
  @DisplayName("A committed-but-unprocessed entry is dispatched after restarting the application")
  public void leftOverEntryIsDispatchedAfterRestart() throws Exception {

    final Long aggregateId;

    // first context: the dispatch fails. No retry happens, because the poll interval is
    // huge. This leaves a committed entry which was never processed, like a crash right
    // after the commit
    try (var context = runApplication("start", "PT1H")) {
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      listener.failNextDispatches(Integer.MAX_VALUE);
      @SuppressWarnings("unchecked")
      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();
      final var transactionTemplate = context.getBean(TransactionTemplate.class);
      final var attachedAggregate = transactionTemplate.execute(status -> {
        final var aggregate = new Aggregate();
        aggregate.setContent("recovery-test");
        return processService.startWorkflow(aggregate);
      });
      assertNotNull(attachedAggregate);
      aggregateId = attachedAggregate.getId();
      // wait for the (failing) dispatch right after the commit. So the next attempt of the
      // entry is scheduled before the context is closed
      listener.awaitInvocations(1, 10000);
      FailedAttempts.awaitWrittenDown(context, 1);
    }

    // second context: the first poll at startup must recover the entry
    try (var context = runApplication("start", "PT0.5S")) {
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      final var invocations = listener.awaitInvocations(1, 15000);
      assertEquals(aggregateId, invocations.getFirst());
    }

  }

  @Test
  @DisplayName("Old and new operations recover together: a COMPLETE_TASK entry survives a restart, too")
  public void completeTaskEntryIsDispatchedAfterRestart() throws Exception {

    final Long aggregateId;

    // the restarted context must answer ACTIVE right away, because the election at
    // dispatch time probes again on recovery. WORKFLOW probes answer UNKNOWN on purpose.
    // They serve the mitigation for a START dispatched again. Otherwise that mitigation
    // would use up the recovered START entry instead of dispatching it. That path has
    // its own test (OutboxRedispatchMitigationTest)
    SteerableTaskAwarenessSource.initialAnswer = io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE;
    SteerableTaskAwarenessSource.initialWorkflowAnswer = io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    try {

      // first context: schedule a completion whose dispatch fails. This leaves a committed
      // COMPLETE_TASK entry which was never processed, as after a crash. The task
      // operations do not change the outbox STORE. So recovery works for the new
      // operation exactly as for START_WORKFLOW
      try (var context = runApplication("tasks", "PT1H")) {
        final var listener = context.getBean(RecordingPhaseTwoListener.class);
        listener.failNextDispatches(Integer.MAX_VALUE);
        @SuppressWarnings("unchecked")
        final var processService = (ProcessService<Aggregate>) context
            .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
            .getObject();
        final var transactionTemplate = context.getBean(TransactionTemplate.class);
        final var attachedAggregate = transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("task-recovery-test");
          return processService.startWorkflow(aggregate);
        });
        assertNotNull(attachedAggregate);
        aggregateId = attachedAggregate.getId();
        // phase two of the start fails, too. That entry stays as well, and it proves that
        // both operations dispatch after the restart
        listener.awaitInvocations(1, 10000);
        transactionTemplate.executeWithoutResult(status -> processService
            .completeTask(attachedAggregate, "task-recovered"));
        // the start and the completion must both carry their failed attempt before the
        // context goes away. Otherwise this tests something else
        FailedAttempts.awaitWrittenDown(context, 2);
      }

      // second context: BOTH the old (START_WORKFLOW) and the new (COMPLETE_TASK) entry
      // must dispatch
      try (var context = runApplication("tasks", "PT0.5S")) {
        final var listener = context.getBean(RecordingPhaseTwoListener.class);
        final var invocations = listener.awaitInvocations(1, 15000);
        assertEquals(aggregateId, invocations.getFirst());
        final var completions = listener.awaitCompletedTasks(1, 15000);
        assertEquals(
            aggregateId
                + ":task-recovered",
            completions.getFirst());
      }

    } finally {
      SteerableTaskAwarenessSource.initialAnswer = io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
      SteerableTaskAwarenessSource.initialWorkflowAnswer = null;
    }

  }

}
