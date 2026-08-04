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

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Crash-recovery ordering (story 26b hard requirement): a committed-but-undispatched
 * phase-two outbox entry left over by a "crashed" previous instance is dispatched by
 * the JDBC outbox dispatcher's startup poll - but never BEFORE the deployment
 * pipeline deployed the BPMN resources and started workflow processing. The entry is
 * seeded into a file-based H2 database before the application boots; the observer
 * priorities (deployment runner before the outbox dispatchers) enforce the ordering,
 * asserted here on the recorded events.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxRecoveryOrderingTest {

  private static final Path DB_DIRECTORY = Path
      .of("target", "outbox-recovery-test")
      .toAbsolutePath();

  /**
   * The dispatcher's H2 DDL (see <code>JdbcPhaseTwoOutboxDispatcher</code>) - the
   * table is seeded BEFORE boot, the dispatcher then skips its own creation.
   */
  private static final String CREATE_TABLE = """
      CREATE TABLE VANILLABP_PHASE_TWO_OUTBOX (\
      ID VARCHAR(36) PRIMARY KEY, \
      WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
      BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
      OPERATION VARCHAR(255) NOT NULL, \
      AGGREGATE_ID VARCHAR(1024), \
      ADAPTER_ID VARCHAR(255), \
      ARGS VARCHAR(2048), \
      IDEMPOTENCY_KEY VARCHAR(512) UNIQUE, \
      STATUS VARCHAR(16) NOT NULL, \
      CREATED_AT TIMESTAMP NOT NULL, \
      ATTEMPTS INT NOT NULL, \
      NEXT_ATTEMPT_AT TIMESTAMP NOT NULL, \
      DONE_AT TIMESTAMP)""";

  private static final String INSERT_ENTRY = """
      INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, IDEMPOTENCY_KEY, \
      STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, 'test-module', 'WorkflowService', 'START_WORKFLOW', '42', 'demo', \
      'test-module|WorkflowService|42', 'OPEN', ?, 0, ?)""";

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
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(CREATE_TABLE);
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
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
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
