package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.inject.Inject;

/**
 * What happens when the dispatch got through and the write which says so did not.
 * <p>
 * That write runs after the renewal of the lease was stopped. So it stands outside the
 * attempt and outside the lane's own error handling. An exception there used to end the
 * thread of the lane without a word. The entry then stayed claimed until its lease ran out.
 * The operation had reached the BPMS, and nothing said what had become of it. Now it is
 * reported and planned again, like any attempt which did not get through. This test reads
 * that the entry is planned again.
 * <p>
 * The failing write is a real one, not a double. A dispatched entry writes its own id into
 * the field <code>dedupKey</code>, which has a unique index. So an entry cannot be marked if
 * another document already holds its id there. A store hands back the same error when two
 * nodes race, and nothing has to be mocked.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoAMarkWhichDidNotGetThroughIsPlannedAgainTest {

  private static final String DATABASE = "outbox-failed-mark-it";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "WorkflowService";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      // long enough that exactly one attempt happens while this test looks
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT30S");

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  MongoClient mongoClient;

  /**
   * The outbox of the application. It is asked through the reader, so this test names
   * neither a collection nor a field.
   */
  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase(DATABASE));

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    outbox().removeAllEntries();

  }

  /**
   * Writes the entry to be dispatched. Next to it, it writes a dispatched entry which already
   * holds that entry's id as its <code>dedupKey</code>. So the mark of the first one cannot be
   * written.
   *
   * @return The id of the entry to be dispatched
   */
  private String anEntryWhoseMarkCannotBeWritten() {

    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();
    outbox()
        .writeDispatchedEntry(
            UUID.randomUUID().toString(), MODULE, PROCESS, "START_WORKFLOW", "another-aggregate", "test", id, now);
    outbox()
        .writeWaitingEntry(
            id,
            MODULE,
            PROCESS,
            "START_WORKFLOW",
            "failed-mark-aggregate",
            "test",
            "the-key-of-"
                + id,
            now,
            now);
    return id;

  }

  private MongoPhaseTwoOutboxReader.Entry entryOf(
      final String id) {

    return outbox()
        .entryById(id)
        .orElseThrow(() -> new AssertionError("the entry is gone"));

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("An entry whose mark did not get through is planned again")
  public void aMarkWhichDidNotGetThroughIsPlannedAgain() throws Exception {

    final var entry = anEntryWhoseMarkCannotBeWritten();

    listener.awaitInvocations(1, UNTIL_IT_HAPPENED);
    waitUntil(
        "the entry whose mark could not be written was never planned again",
        () -> entryOf(entry).attempts() == 1);

    final var afterTheAttempt = entryOf(entry);
    assertTrue(
        afterTheAttempt.isWaiting(),
        "an entry whose mark did not get through is still waiting for one");
    assertTrue(
        afterTheAttempt.nextAttemptAt().isAfter(Instant.now()),
        "the entry was not planned for a later moment");

  }

}
