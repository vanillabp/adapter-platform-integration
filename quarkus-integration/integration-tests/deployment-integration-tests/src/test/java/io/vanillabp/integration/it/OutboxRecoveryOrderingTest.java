package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import jakarta.inject.Inject;

/**
 * Crash-recovery ordering, a hard requirement: a committed-but-undispatched
 * phase-two outbox entry left over by a "crashed" previous instance is dispatched by
 * the JDBC outbox dispatcher's startup poll - but never BEFORE the deployment
 * pipeline deployed the BPMN resources and started workflow processing. The entry is
 * seeded into a file-based H2 database before the application boots; the observer
 * priorities (deployment runner before the outbox dispatchers) enforce the ordering,
 * asserted here on the recorded events.
 * <p>
 * The table is created with the statement the store itself creates it with, and the entry
 * is written into the table the store reads. The DDL used to stand in this file, where it
 * described a table nothing checked: the store could have moved a column and this test
 * would have gone on seeding the old one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxRecoveryOrderingTest {

  private static final Path DB_DIRECTORY = Path
      .of("target", "outbox-recovery-test")
      .toAbsolutePath();

  /**
   * The outbox the application under test runs, named where it is declared rather than here.
   */
  private static final String OUTBOX_TABLE = PhaseTwoOutboxReader.defaultOutboxTableName();

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, IDEMPOTENCY_KEY, \
      DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, 'test-module', 'WorkflowService', 'START_WORKFLOW', '42', 'demo', \
      'START_WORKFLOW|test-module|WorkflowService|42', 'START_WORKFLOW|test-module|WorkflowService|42', \
      'OPEN', ?, 0, ?)""".formatted(OUTBOX_TABLE);

  private static void seedCrashedOutboxEntry() {

    try {
      if (Files.exists(DB_DIRECTORY)) {
        try (var files = Files.walk(DB_DIRECTORY)) {
          files
              .sorted(Comparator.reverseOrder())
              .forEach(path -> {
                try {
                  Files.delete(path);
                } catch (final IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:file:"
            + DB_DIRECTORY.resolve("outbox")
            + ";DB_CLOSE_DELAY=-1")) {
      // the table is built by the store which reads it, not described here a second time:
      // a test writing its own DDL keeps working while the store moves on to another table
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(JdbcPhaseTwoOutboxDispatcher.createTableStatement(connection, OUTBOX_TABLE));
        for (final var index : JdbcPhaseTwoOutboxDispatcher.INDEXES) {
          statement.executeUpdate(index.createOn(OUTBOX_TABLE));
        }
      }
      try (var statement = connection.prepareStatement(INSERT_ENTRY)) {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setTimestamp(2, Timestamp.from(Instant.now()));
        statement.setTimestamp(3, Timestamp.from(Instant.now().minusSeconds(60)));
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      throw new IllegalStateException("could not seed the crashed outbox entry", e);
    }

  }

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setBeforeAllCustomizer(OutboxRecoveryOrderingTest::seedCrashedOutboxEntry)
      .withApplicationRoot(jar -> jar
          .addAsResource("outbox-recovery/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingDeploymentEvents events;

  @Test
  @DisplayName("A recovered phase-two entry is dispatched only AFTER deployment and start of workflow processing")
  public void recoveredEntryDispatchedAfterProcessingStarted() throws Exception {

    // the seeded entry has to be dispatched by the dispatcher's startup poll
    // (aggregate ID converted back from '42' to the aggregate's Long ID type)
    final var recorded = events.awaitEvent("phaseTwo:", 10000);

    final var deployIndex = recorded.indexOf("adapter:demo:deployResources:test-module");
    final var startIndex = recorded.indexOf("adapter:demo:startWorkflowProcessing:test-module");
    final var phaseTwoIndex = recorded.indexOf("phaseTwo:42");

    assertTrue(deployIndex != -1, "deployment did not run: "
        + recorded);
    assertTrue(startIndex != -1, "workflow processing was not started: "
        + recorded);
    assertTrue(phaseTwoIndex != -1, "the recovered entry was not dispatched: "
        + recorded);
    assertTrue(
        (deployIndex < phaseTwoIndex) && (startIndex < phaseTwoIndex),
        "the recovered phase-two entry was dispatched before the deployment pipeline finished: "
            + recorded);

  }

}
