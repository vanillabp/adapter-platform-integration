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
 * Simulates crash recovery of the gruelbox-based JPA phase-two outbox: a first
 * application context leaves a committed-but-unprocessed entry in the database (the
 * dispatch fails and the poll interval is too long for a retry - like a crashed
 * instance). A second application context using the same (in-memory, kept-alive) H2
 * database has to dispatch the left-over entry on startup.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxRecoveryTest {

  // one database PER TEST: the kept-alive in-memory database outlives a test while
  // Hibernate recreates the aggregate table on every context start - reused
  // aggregate IDs would collide with idempotency keys of a previous test's entries
  private static final String DATASOURCE_URL_PATTERN = "jdbc:h2:mem:outbox-recovery-%s;DB_CLOSE_DELAY=-1";

  /**
   * Runs the test application using an own H2 database (not shared with other test
   * contexts). The configuration is passed as command-line arguments since those take
   * precedence over <code>application.yaml</code>.
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

    // first context: dispatch fails once; no retry happens since the poll interval
    // is huge - this leaves a committed-but-unprocessed entry, like a crash right
    // after the commit would
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
      // wait for the (failing) immediate post-commit dispatch, so the entry's next
      // attempt is scheduled before the context is closed
      listener.awaitInvocations(1, 10000);
      // give the outbox time to persist the failed attempt before "crashing"
      Thread.sleep(1000);
    }

    // second context: the initial poll on startup has to recover the entry
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

    // the restarted context has to answer ACTIVE right away - the dispatch-time
    // election probes again on recovery. WORKFLOW probes answer UNKNOWN on
    // purpose: they serve the START re-dispatch mitigation (story 25), which
    // would otherwise consume the recovered START entry instead of dispatching
    // it - that path has its own test (OutboxRedispatchMitigationTest)
    SteerableTaskAwarenessSource.initialAnswer = io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE;
    SteerableTaskAwarenessSource.initialWorkflowAnswer = io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    try {

      // first context: schedule a completion whose dispatch fails - leaving a
      // committed-but-unprocessed COMPLETE_TASK entry (the crash shape); the
      // outbox STORE is untouched by story 22, so recovery works for the new
      // operation exactly like for START_WORKFLOW
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
        // the start's phase two fails, too - that entry stays as well and proves
        // both operations dispatch after the restart
        listener.awaitInvocations(1, 10000);
        transactionTemplate.executeWithoutResult(status -> processService
            .completeTask(attachedAggregate, "task-recovered"));
        Thread.sleep(1000);
      }

      // second context: BOTH the old (START_WORKFLOW) and the new (COMPLETE_TASK)
      // entry have to dispatch
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
