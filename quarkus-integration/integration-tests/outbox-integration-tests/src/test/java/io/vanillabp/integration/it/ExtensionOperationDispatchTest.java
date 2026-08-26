package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SampleExtension;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The outbox is open to extensions: an operation registered by
 * {@link SampleExtension} is scheduled inside the business transaction, dispatched
 * to the extension's own handler after the commit, deduplicated by the extension's
 * own idempotency key and retried when the handler fails - the same guarantees the
 * core operations get, without any core code knowing the operation.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionOperationDispatchTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          // an own database: the sibling tests of this module count rows of the
          // outbox store they share within the JVM
          .addAsResource("extension-operation.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SampleExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:extension-operation-dispatch-it;DB_CLOSE_DELAY=-1");

  private static final String COUNT_ENTRIES_OF_OPERATION = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE OPERATION = '%s'".formatted(SampleExtension.OPERATION_NAME);

  @Inject
  WorkflowService workflowService;

  @Inject
  SampleExtension extension;

  @Inject
  PhaseTwoOutbox outbox;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private long count(
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  private Aggregate startWorkflowAndSchedule(
      final String content,
      final String event) throws Exception {

    userTransaction.begin();
    final Aggregate attached;
    try {
      attached = workflowService.startWorkflow(content);
      outbox
          .schedule(
              SampleExtension.call("test-module", "dummy", attached.getId().toString(), event));
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();
    return attached;

  }

  @Test
  @DisplayName("An extension's operation is dispatched to the extension after the commit")
  public void extensionOperationIsDispatchedAfterCommit() throws Exception {

    final var aggregate = startWorkflowAndSchedule("extension-dispatch", "created");
    assertNotNull(aggregate.getId());

    final var dispatched = extension.awaitDispatched(1, 10000);
    final var call = dispatched.getFirst();
    assertEquals(SampleExtension.OPERATION_NAME, call.operation());
    assertEquals(aggregate.getId().toString(), call.workflowAggregateId());
    // the arguments travel with the entry - the store persists them without ever
    // interpreting them
    assertEquals("created", call.args().get(SampleExtension.ARG_EVENT));

    // the entry is stored under the extension's operation NAME (the store knows
    // nothing else about it)
    assertTrue(count(COUNT_ENTRIES_OF_OPERATION) > 0);

  }

  @Test
  @DisplayName("The extension's own idempotency key deduplicates its planned entries")
  public void extensionOperationIsDeduplicatedByItsOwnKey() throws Exception {

    // same aggregate, same event, and the first entry still waiting for its dispatch:
    // the key repeats, so scheduling is a no-op
    userTransaction.begin();
    final var aggregate = workflowService.startWorkflow("extension-dedup");
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    final var scheduledAgain = outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    userTransaction.commit();
    assertFalse(scheduledAgain);

    extension.awaitDispatched(1, 10000);

    // a DIFFERENT event of the same workflow is a different key and is dispatched
    userTransaction.begin();
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "completed"));
    userTransaction.commit();

    final var dispatched = extension.awaitDispatched(2, 10000);
    assertEquals("created", dispatched.get(0).args().get(SampleExtension.ARG_EVENT));
    assertEquals("completed", dispatched.get(1).args().get(SampleExtension.ARG_EVENT));

    // and the very same event again, now that the first one reached the extension: a
    // new operation, because the key deduplicates what is planned
    userTransaction.begin();
    final var scheduledAfterDispatch = outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", aggregate.getId().toString(), "created"));
    userTransaction.commit();
    assertTrue(scheduledAfterDispatch);
    extension.awaitDispatched(3, 10000);

  }

  @Test
  @DisplayName("A failing extension dispatch is retried")
  public void failingExtensionDispatchIsRetried() throws Exception {

    extension.failNextDispatches(1);

    final var aggregate = startWorkflowAndSchedule("extension-retry", "created");

    // the first attempt threw, the retry succeeds
    final var dispatched = extension.awaitDispatched(1, 10000);
    assertEquals(aggregate.getId().toString(), dispatched.getFirst().workflowAggregateId());

  }

  @Test
  @DisplayName("On rollback the extension's entry is gone and never dispatched")
  public void rollbackLeavesNoExtensionEntry() throws Exception {

    final var entriesBefore = count(COUNT_ENTRIES_OF_OPERATION);

    userTransaction.begin();
    final var attached = workflowService.startWorkflow("extension-rollback");
    outbox
        .schedule(
            SampleExtension.call("test-module", "dummy", attached.getId().toString(), "rolled-back"));
    userTransaction.rollback();

    // the entry rode the rolled-back transaction
    assertEquals(entriesBefore, count(COUNT_ENTRIES_OF_OPERATION));

    // wait longer than the poll interval: nothing may ever be dispatched
    Thread.sleep(1500);
    assertTrue(extension.getDispatched().isEmpty());

  }

}
