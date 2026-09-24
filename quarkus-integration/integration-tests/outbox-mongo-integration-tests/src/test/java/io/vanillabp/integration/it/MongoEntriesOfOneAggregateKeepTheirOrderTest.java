package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.stream.IntStream;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PayloadExtension;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The two promises the MongoDB store makes now that it dispatches on more than one thread:
 * what belongs to one workflow aggregate keeps its order, and what belongs to different ones
 * travels at the same time.
 * <p>
 * What this adds to {@code DispatchLanesTest}, which holds the rule itself without a database,
 * is the wiring of this store: that the claim really reads the entries in the order they were
 * written, and that the dispatcher really hands them to the lanes instead of dispatching them
 * where it claims them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoEntriesOfOneAggregateKeepTheirOrderTest {

  private static final String DATABASE = "outbox-lanes-it";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String MODULE = "test-module";

  private static final String PROCESS = "WorkflowService";

  /**
   * How many operations of one workflow wait for their dispatch at the same moment. Ten of
   * them: enough that a dispatcher which took its entries in any other order would be caught.
   * Most of them wait in the table meanwhile, because a lane takes one entry beyond the one it
   * dispatches, and the order is what the poller hands them over in either way.
   */
  private static final int OPERATIONS_OF_ONE_WORKFLOW = 10;

  private static final int LANES = 4;

  /**
   * When every entry of a test is due. One moment for all of them, so the order they are
   * claimed in can only come from the moment they were written.
   */
  private static final Instant DUE_AT = Instant.now().minusSeconds(30);

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(PayloadExtension.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("vanillabp.outbox.dispatch-threads", String.valueOf(LANES));

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  PayloadExtension extension;

  @Inject
  MongoClient mongoClient;

  private MongoCollection<Document> outbox() {

    return mongoClient
        .getDatabase(DATABASE)
        .getCollection("vanillabp-phase-two-outbox");

  }

  @BeforeEach
  public void startFromAnEmptyCollection() {

    listener.reset();
    extension.reset();
    outbox().deleteMany(new Document());

  }

  @AfterEach
  public void letGoOfWhatIsStillHeld() {

    listener.reset();

  }

  /**
   * Writes one entry which is due right away.
   *
   * @param operation The name of the operation the entry carries
   * @param aggregateId The aggregate the operation belongs to
   * @param args What the dispatch is handed, or <code>null</code>
   * @param writtenAt When the entry was written
   */
  private void anEntryDueNow(
      final String operation,
      final String aggregateId,
      final Document args,
      final Instant writtenAt) {

    final var id = UUID.randomUUID().toString();
    // every entry of a test is due at the same moment, and only the moment it was written
    // tells them apart. An entry which failed once carries a due time of its own anyway, so
    // the claim cannot read the order off that field - it has to sort by the other one
    outbox()
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", operation)
            .append("aggregateId", aggregateId)
            .append("adapterId", "test")
            .append("args", args)
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", "OPEN")
            .append("createdAt", Date.from(writtenAt))
            .append("attempts", 0)
            .append("nextAttemptAt", Date.from(DUE_AT))
            .append("leasedBy", null)
            .append("leasedUntil", null));

  }

  private long dispatched() {

    return outbox().countDocuments(Filters.eq("status", "DONE"));

  }

  private void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("Ten operations of one workflow arrive in the order they were written")
  public void operationsOfOneWorkflowArriveInOrder() throws Exception {

    final var writtenAt = Instant.now().minusSeconds(60);
    // written into the collection the other way round, so the order of the documents is not
    // the order of the moments. A claim which reads whatever the collection answers first
    // would then hand the entries to the lane the wrong way round, and this test says so
    IntStream
        .iterate(OPERATIONS_OF_ONE_WORKFLOW - 1, position -> position >= 0, position -> position - 1)
        // a millisecond apart, because two entries written in the same one would leave their
        // order to the collection and this test would be about that instead
        .forEach(position -> anEntryDueNow(
            PayloadExtension.OPERATION_NAME,
            "one-aggregate",
            new Document(PayloadExtension.ARG_EVENT, eventOf(position)),
            writtenAt.plusMillis(position)));

    final var dispatchedCalls = extension.awaitDispatched(OPERATIONS_OF_ONE_WORKFLOW, UNTIL_IT_HAPPENED);

    assertEquals(
        IntStream
            .range(0, OPERATIONS_OF_ONE_WORKFLOW)
            .mapToObj(MongoEntriesOfOneAggregateKeepTheirOrderTest::eventOf)
            .toList(),
        dispatchedCalls
            .stream()
            .map(PhaseTwoCall::args)
            .map(args -> args.get(PayloadExtension.ARG_EVENT))
            .toList(),
        "two operations of one workflow overtook each other");

  }

  @Test
  @DisplayName("Two operations of different workflows really travel at the same time")
  public void operationsOfDifferentWorkflowsTravelTogether() throws Exception {

    listener.letDispatchesMeet(2);
    // two aggregates whose lanes differ - two which shared one would wait for each other
    // however many lanes there are, and the test would be about the hash instead
    final var aggregates = twoAggregatesOnDifferentLanes();
    final var writtenAt = Instant.now().minusSeconds(60);
    anEntryDueNow("START_WORKFLOW", aggregates[0], null, writtenAt);
    anEntryDueNow("START_WORKFLOW", aggregates[1], null, writtenAt.plusMillis(1));

    // both dispatches let each other out, so both entries are marked. Where one lane served
    // them the second one would still be waiting for its turn when the first one gave up
    waitUntil("the two operations did not travel at the same time", () -> dispatched() == 2);

    assertEquals(2, listener.getInvocations().size(), "both operations have to reach the adapter");

  }

  /**
   * Two aggregates the lanes keep apart, read from the rule the dispatcher applies rather than
   * guessed.
   *
   * @return The ids of two aggregates served by different lanes
   */
  private static String[] twoAggregatesOnDifferentLanes() {

    final var first = "aggregate-0";
    final var firstLane = DispatchLanes.laneOf(orderingKeyOf(first), LANES);
    for (var candidate = 1; candidate < 100; candidate++) {
      final var other = "aggregate-"
          + candidate;
      if (DispatchLanes.laneOf(orderingKeyOf(other), LANES) != firstLane) {
        return new String[]{
            first, other
        };
      }
    }
    throw new IllegalStateException("no two of a hundred aggregates land on different lanes");

  }

  private static String orderingKeyOf(
      final String aggregateId) {

    return "%s|%s|%s".formatted(MODULE, PROCESS, aggregateId);

  }

  private static String eventOf(
      final int position) {

    return "event-%02d".formatted(position);

  }

}
