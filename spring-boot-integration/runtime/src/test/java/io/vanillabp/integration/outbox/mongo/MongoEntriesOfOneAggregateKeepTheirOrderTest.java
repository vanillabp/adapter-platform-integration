package io.vanillabp.integration.outbox.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * The two promises the MongoDB store makes now that it dispatches on more than one thread:
 * what belongs to one workflow aggregate keeps its order, and what belongs to different ones
 * travels at the same time.
 * <p>
 * What this adds to {@code DispatchLanesTest}, which holds the rule itself without a
 * database, is the wiring of this store: that the claim really reads the entries in the order
 * they were written, and that the dispatcher really hands them to the lanes instead of
 * dispatching them where it claims them.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class MongoEntriesOfOneAggregateKeepTheirOrderTest {

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

  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String DATABASE = "lanes-test";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * The argument every entry carries so the dispatches can be told apart. The aggregate alone
   * cannot do it: the entries of the ordering test all belong to the same one.
   */
  private static final String ARG_POSITION = "position";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @Mock
  private MigrationProcessService<Object> node;

  /**
   * What reached the adapter, in the order it arrived there.
   */
  private final List<String> arrived = new CopyOnWriteArrayList<>();

  /**
   * Released by the dispatches themselves once enough of them are inside the adapter at the
   * same time. A dispatcher which serialises everything never releases it.
   */
  private final CountDownLatch dispatchesInsideAtTheSameTime = new CountDownLatch(2);

  private MongoTemplate mongoTemplate;

  private MongoClient mongoClient;

  private MongoPhaseTwoOutboxDispatcher dispatcher;

  @BeforeEach
  public void connectToTheDatabase() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE));

  }

  @AfterEach
  public void stopTheDispatcher() {

    if (dispatcher != null) {
      dispatcher.stopPolling();
      dispatcher = null;
    }
    mongoClient.close();

  }

  /**
   * Builds the dispatcher of this test on its own collection, with the lanes it is about.
   *
   * @param collection The collection of this test
   * @param waitForEachOther Whether every dispatch waits until two of them are inside
   */
  private void dispatcherOn(
      final String collection,
      final boolean waitForEachOther) {

    when(node.getWorkflowModuleId()).thenReturn(MODULE);
    when(node.getBpmnProcessId()).thenReturn(PROCESS);
    when(node.convertAggregateId(Mockito.anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    Mockito
        .doAnswer(invocation -> {
          final java.util.Map<String, String> args = invocation.getArgument(3);
          arrived.add(args.get(ARG_POSITION));
          if (waitForEachOther) {
            dispatchesInsideAtTheSameTime.countDown();
            dispatchesInsideAtTheSameTime.await(UNTIL_IT_HAPPENED.toSeconds(), TimeUnit.SECONDS);
          }
          return null;
        })
        .when(node)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(node);
    final var properties = new PhaseTwoOutboxProperties();
    properties.setDispatchThreads(LANES);
    properties.setPollInterval(Duration.ofSeconds(1));
    dispatcher = new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(router), properties, collection, theOnly(VanillaBpMetrics.NONE));

  }

  /**
   * One bean, as the dispatcher is handed it. A running application gets these from its
   * container; a test which builds the dispatcher itself has to say what they answer.
   *
   * @param <T> What the provider stands for
   * @param bean The bean to hand out
   * @return A provider answering with that bean
   */
  private static <T> ObjectProvider<T> theOnly(
      final T bean) {

    return new ObjectProvider<T>() {

      @Override
      public T getObject() {

        return bean;

      }

      @Override
      public Stream<T> stream() {

        return Stream.of(bean);

      }

    };

  }

  /**
   * Writes one entry which is due right away.
   *
   * @param collection The collection of this test
   * @param aggregateId The aggregate the operation belongs to
   * @param position What the dispatch reports about this entry
   * @param writtenAt When the entry was written
   */
  private void anEntryDueNow(
      final String collection,
      final String aggregateId,
      final String position,
      final Instant writtenAt) {

    final var id = UUID.randomUUID().toString();
    // every entry of a test is due at the same moment, and only the moment it was written
    // tells them apart. An entry which failed once carries a due time of its own anyway, so
    // the claim cannot read the order off that field - it has to sort by the other one
    mongoTemplate
        .getCollection(collection)
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.START_WORKFLOW.name())
            .append("aggregateId", aggregateId)
            .append("adapterId", "dummy")
            .append("args", new Document(ARG_POSITION, position))
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", PhaseTwoOutboxEntry.STATUS_OPEN)
            .append("createdAt", Date.from(writtenAt))
            .append("attempts", 0)
            .append("nextAttemptAt", Date.from(DUE_AT))
            .append("leasedBy", null)
            .append("leasedUntil", null));

  }

  private long dispatchedIn(
      final String collection) {

    return mongoTemplate
        .getCollection(collection)
        .countDocuments(Filters.eq("status", PhaseTwoOutboxEntry.STATUS_DONE));

  }

  private void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("Ten operations of one workflow arrive in the order they were written")
  public void operationsOfOneWorkflowArriveInOrder() throws Exception {

    final var collection = "outbox-one-aggregate-in-order";
    dispatcherOn(collection, false);
    final var writtenAt = Instant.now().minusSeconds(60);
    // written into the collection the other way round, so the order of the documents is not
    // the order of the moments. A claim which reads whatever the collection answers first
    // would then hand the entries to the lane the wrong way round, and this test says so
    IntStream
        .iterate(OPERATIONS_OF_ONE_WORKFLOW - 1, position -> position >= 0, position -> position - 1)
        // a millisecond apart, because two entries written in the same one would leave
        // their order to the collection and this test would be about that instead
        .forEach(position -> anEntryDueNow(
            collection, "one-aggregate", positionOf(position), writtenAt.plusMillis(position)));

    dispatcher.startPolling();
    waitUntil(
        "not every entry was dispatched",
        () -> dispatchedIn(collection) == OPERATIONS_OF_ONE_WORKFLOW);

    assertEquals(
        IntStream
            .range(0, OPERATIONS_OF_ONE_WORKFLOW)
            .mapToObj(MongoEntriesOfOneAggregateKeepTheirOrderTest::positionOf)
            .toList(),
        List.copyOf(arrived),
        "two operations of one workflow overtook each other");

  }

  @Test
  @DisplayName("Two operations of different workflows really travel at the same time")
  public void operationsOfDifferentWorkflowsTravelTogether() throws Exception {

    final var collection = "outbox-two-aggregates-at-once";
    dispatcherOn(collection, true);
    // two aggregates whose lanes differ - two which shared one would wait for each other
    // however many lanes there are, and the test would be about the hash instead
    final var aggregates = twoAggregatesOnDifferentLanes();
    final var writtenAt = Instant.now().minusSeconds(60);
    anEntryDueNow(collection, aggregates[0], positionOf(0), writtenAt);
    anEntryDueNow(collection, aggregates[1], positionOf(1), writtenAt.plusMillis(1));

    dispatcher.startPolling();
    // both dispatches let each other out, so both entries are marked. Where one lane served
    // them the second one would still be waiting for its turn when the first one gave up
    waitUntil("the two operations did not travel at the same time", () -> dispatchedIn(collection) == 2);

    assertEquals(2, arrived.size(), "both operations have to reach the adapter");

  }

  /**
   * Two aggregates the lanes keep apart, read from the rule the dispatcher applies rather
   * than guessed.
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

  private static String positionOf(
      final int position) {

    return "position-%02d".formatted(position);

  }

}
