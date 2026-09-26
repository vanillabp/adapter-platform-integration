package io.vanillabp.integration.runtime.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.MongoInterruptedException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.test.InstanceDouble;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * A dispatch which fails while the node is being stopped, and cannot write down that it
 * failed either - the Quarkus half of it.
 * <p>
 * The Spring Boot store has the same test, and the two are not one test run twice: the
 * dispatchers are two classes, and what is asked here is whether THIS one keeps a failed
 * write inside its lane. An operator of a Quarkus application otherwise reads "Exception in
 * thread vanillabp-outbox-dispatch-1" and a stack trace with nothing around it, on a
 * shutdown where nothing is wrong.
 * <p>
 * The one write of this story is made to fail by a spy over the real collection, and only
 * that write: an interruption cannot be produced by timing without making the test depend on
 * which of the driver's waits happens to be reached. Everything else - the entry, the claim,
 * the lanes, the poller - is the real store on a real MongoDB. The dispatcher of this
 * platform reads its configuration from the config of the running application, so the test
 * registers one of its own for as long as it runs.
 * <p>
 * The class sits in the package of the dispatcher and not with the other tests of this
 * module: the container fills the dispatcher's injection points, and the test does the same
 * thing, which only a neighbour of the class can do.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class MongoDispatchInterruptedWhileStoppingTest {

  private static final String LEASE = "PT3S";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String DATABASE = "interrupted-dispatch-test";

  private static final String COLLECTION = "outbox-interrupted-dispatch";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> theNode;

  private final CountDownLatch theDispatchWasTried = new CountDownLatch(1);

  /**
   * Set once the dispatch failed, so only the write which follows a failed dispatch is the
   * one made to fail. The claim, the renewal and the test's own writes go to the database.
   */
  private final AtomicBoolean theDispatchFailed = new AtomicBoolean();

  private final AtomicBoolean theWriteAlreadyFailed = new AtomicBoolean();

  private final List<LogRecord> whatWasLogged = Collections.synchronizedList(new ArrayList<>());

  private Logger dispatcherLog;

  private Handler collectWhatIsSaid;

  private MongoClient mongoClient;

  private MongoCollection<Document> outbox;

  private SmallRyeConfig config;

  /**
   * What left the thread of a lane, which is what this story is about.
   */
  private final List<Throwable> outOfALaneThread = Collections.synchronizedList(new ArrayList<>());

  private Thread.UncaughtExceptionHandler handlerOfThisJvm;

  @BeforeEach
  public void watchTheLogAndTheThreads() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    outbox = mongoClient
        .getDatabase(DATABASE)
        .getCollection(COLLECTION);
    outbox.deleteMany(new Document());

    config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(
            new PropertiesConfigSource(
                Map
                    .of(
                        "quarkus.mongodb.database", DATABASE,
                        "vanillabp.outbox.mongo.collection", COLLECTION,
                        "vanillabp.outbox.attempt-frequency", LEASE,
                        "vanillabp.outbox.poll-interval", LEASE), "the test", 500))
        .build();
    ConfigProviderResolver
        .instance()
        .registerConfig(config, Thread.currentThread().getContextClassLoader());

    // Quarkus logs through the JBoss LogManager, so what the dispatcher says is collected
    // where that log manager hands it over
    whatWasLogged.clear();
    collectWhatIsSaid = new Handler() {

      @Override
      public void publish(
          final LogRecord record) {

        whatWasLogged.add(record);

      }

      @Override
      public void flush() {

      }

      @Override
      public void close() {

      }

    };
    dispatcherLog = Logger.getLogger(MongoPhaseTwoOutboxDispatcher.class.getName());
    dispatcherLog.setLevel(Level.ALL);
    dispatcherLog.addHandler(collectWhatIsSaid);

    handlerOfThisJvm = Thread.getDefaultUncaughtExceptionHandler();
    Thread
        .setDefaultUncaughtExceptionHandler((
            thread,
            failure) -> outOfALaneThread.add(failure));

  }

  @AfterEach
  public void stopWatching() {

    Thread.setDefaultUncaughtExceptionHandler(handlerOfThisJvm);
    dispatcherLog.removeHandler(collectWhatIsSaid);
    ConfigProviderResolver
        .instance()
        .releaseConfig(config);
    mongoClient.close();

  }

  @Test
  @DisplayName("A stop during a dispatch leaves a line about a stop, and no stack trace out of a lane")
  public void aStopLeavesALineAndNoStackTrace() throws Exception {

    final var dispatcher = aDispatcherWhoseNextWriteIsInterrupted();
    final var entry = anEntryDueNow();

    try {
      dispatcher.onStart(new StartupEvent());
      assertTrue(
          theDispatchWasTried.await(UNTIL_IT_HAPPENED, TimeUnit.MILLISECONDS),
          "the entry was never dispatched");
      waitUntil(
          "nothing was said about the entry whose result could not be written",
          () -> lineAbout(entry, "because this node is stopping") != null);
    } finally {
      dispatcher.shutdown();
    }

    final var line = lineAbout(entry, "because this node is stopping");
    // a stop is the ordinary case, so it is said the way a stop is said elsewhere
    assertEquals(Level.INFO, line.getLevel());
    assertTrue(line.getMessage().contains("was interrupted"), line.getMessage());
    assertTrue(
        line.getMessage().contains("dispatched once its lease runs out"),
        line.getMessage());
    assertNull(line.getThrown(), "a stop was reported with a stack trace");
    assertEquals(
        List.of(),
        outOfALaneThread,
        "an exception left the thread of a lane, where nobody can place it");

  }

  @Test
  @DisplayName("The entry stays as it was, so the next node picks it up")
  public void theEntryIsUntouched() throws Exception {

    final var dispatcher = aDispatcherWhoseNextWriteIsInterrupted();
    final var entry = anEntryDueNow();

    try {
      dispatcher.onStart(new StartupEvent());
      assertTrue(theDispatchWasTried.await(UNTIL_IT_HAPPENED, TimeUnit.MILLISECONDS));
      waitUntil(
          "nothing was said about the entry whose result could not be written",
          () -> lineAbout(entry, "because this node is stopping") != null);
    } finally {
      dispatcher.shutdown();
    }

    final var document = outbox
        .find(Filters.eq("_id", entry))
        .first();
    assertNotNull(document, "the entry is gone");
    assertEquals(
        MongoPhaseTwoOutbox.STATUS_OPEN,
        document.getString("status"),
        "an entry whose result was never written stays open");

  }

  /**
   * The real dispatcher on the real collection, with the one write which follows a failed
   * dispatch answered the way the driver answers a thread which was interrupted while it
   * waited.
   *
   * @return The dispatcher, with its injection points filled the way the container fills
   *         them
   */
  private MongoPhaseTwoOutboxDispatcher aDispatcherWhoseNextWriteIsInterrupted() {

    final var dispatcher = new MongoPhaseTwoOutboxDispatcher();
    dispatcher.mongoClient = InstanceDouble.of(List.of(aClientWhoseOutboxCannotBeWrittenOnce()));
    dispatcher.phaseTwoRouter = InstanceDouble.of(List.of(aRouterWhoseDispatchFails()));
    dispatcher.vanillaBpMetrics = InstanceDouble.of(List.of(VanillaBpMetrics.NONE));
    return dispatcher;

  }

  /**
   * The application's MongoDB client, with the outbox collection answering one write the way
   * an interrupted thread is answered. Everything else is the real client on the real
   * database, the payload collection included.
   *
   * @return The client the dispatcher works on
   */
  private MongoClient aClientWhoseOutboxCannotBeWrittenOnce() {

    @SuppressWarnings("unchecked")
    final MongoCollection<Document> writtenOnceInVain = Mockito
        .mock(MongoCollection.class, delegatesTo(outbox));
    Mockito
        .doAnswer(write -> {
          if (theDispatchFailed.get() && theWriteAlreadyFailed.compareAndSet(false, true)) {
            throw new MongoInterruptedException(
                "Interrupted waiting for lock", new InterruptedException("Interrupted waiting for lock"));
          }
          return write.callRealMethod();
        })
        .when(writtenOnceInVain)
        .updateOne(Mockito.any(Bson.class), Mockito.any(Bson.class));

    final var realDatabase = mongoClient.getDatabase(DATABASE);
    final var database = Mockito.mock(MongoDatabase.class, delegatesTo(realDatabase));
    Mockito
        .doReturn(writtenOnceInVain)
        .when(database)
        .getCollection(COLLECTION);

    final var client = Mockito.mock(MongoClient.class, delegatesTo(mongoClient));
    Mockito
        .doReturn(database)
        .when(client)
        .getDatabase(DATABASE);
    return client;

  }

  /**
   * The line the dispatcher wrote about one entry, or <code>null</code> while it has not
   * written it yet. The id of the entry travels as a parameter of the line, so both are read.
   *
   * @param entry The entry the line has to name
   * @param phrase What the line has to say
   * @return The line, or <code>null</code>
   */
  private LogRecord lineAbout(
      final String entry,
      final String phrase) {

    return List
        .copyOf(whatWasLogged)
        .stream()
        .filter(record -> record.getMessage().contains(phrase))
        .filter(record -> Arrays.toString(record.getParameters()).contains(entry))
        .findFirst()
        .orElse(null);

  }

  private PhaseTwoRouter aRouterWhoseDispatchFails() {

    when(theNode.getWorkflowModuleId()).thenReturn(MODULE);
    when(theNode.getBpmnProcessId()).thenReturn(PROCESS);
    when(theNode.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(dispatch -> {
          theDispatchFailed.set(true);
          theDispatchWasTried.countDown();
          throw new IllegalStateException("the BPMS did not answer");
        })
        .when(theNode)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theNode);
    return router;

  }

  private String anEntryDueNow() {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    outbox
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.START_WORKFLOW.name())
            .append("aggregateId", AGGREGATE)
            .append("adapterId", "dummy")
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", MongoPhaseTwoOutbox.STATUS_OPEN)
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now)
            .append("leasedBy", null)
            .append("leasedUntil", null));
    return id;

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

}
