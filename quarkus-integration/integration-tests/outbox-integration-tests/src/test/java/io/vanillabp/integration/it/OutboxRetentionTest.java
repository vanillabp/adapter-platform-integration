package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Integration test of the retention cleanup of the JDBC/JTA-based phase-two outbox:
 * with a tiny <code>vanillabp.outbox.retention</code> a successfully dispatched
 * (DONE) entry is deleted asynchronously by the poller once the retention period
 * passed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxRetentionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("vanillabp.outbox.retention", "PT1S")
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-retention-it;DB_CLOSE_DELAY=-1");

  private static final String COUNT_ENTRIES_OF_AGGREGATE = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE AGGREGATE_ID = '%s'";

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  private long countEntriesOfAggregate(
      final Aggregate aggregate) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement
            .executeQuery(COUNT_ENTRIES_OF_AGGREGATE.formatted(aggregate.getId()))) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  @Test
  @DisplayName("A DONE entry is deleted asynchronously once the retention period passed")
  public void doneEntryIsDeletedAfterRetention() throws Exception {

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("retention-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);

    // retention PT1S + poll interval PT0.5S: the DONE entry has to be gone soon
    final var deadline = System.currentTimeMillis() + 30_000;
    while (countEntriesOfAggregate(attachedAggregate) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "DONE outbox entry was not deleted after the retention period");
      Thread.sleep(100);
    }

  }

}
