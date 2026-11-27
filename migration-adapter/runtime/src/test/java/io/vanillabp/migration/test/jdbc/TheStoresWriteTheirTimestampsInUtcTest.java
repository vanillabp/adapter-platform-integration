package io.vanillabp.migration.test.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.outbox.PhaseTwoOutboxTransaction;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * The columns which hold a moment have no time zone. So a node which writes a moment and a
 * node which reads it have to agree on one zone, and VanillaBP's stores agree on UTC.
 * <p>
 * Each test writes in one zone of the JVM and reads in another. The two zones are 22 hours
 * apart, the most two places on earth can be: Kiritimati is fourteen hours ahead of UTC, Los
 * Angeles eight behind it in winter. A store which wrote the time of day of its JVM would make
 * an entry due almost a day late, or let a record expire a day early. Both directions run,
 * because each of them hides one of the two mistakes. A moment read as too late passes a
 * check for "not too old", and a moment read as too early passes a check for "already due".
 * <p>
 * Every test runs on H2 and on PostgreSQL, the two databases this repository tests its schema
 * on. Each connection is opened under the zone which is the default at that moment. So the
 * PostgreSQL session takes that zone as well, the way a node in that zone would.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressBackgroundOutput
public class TheStoresWriteTheirTimestampsInUtcTest {

  private static final String KIRITIMATI = "Pacific/Kiritimati";

  private static final String LOS_ANGELES = "America/Los_Angeles";

  private static final String MODULE = "utc-module";

  private static final String PROCESS = "UtcProcess";

  /**
   * How far a moment read back may be from the moment it was written. It covers the time a
   * test takes on a slow machine. It is far below the hours a wrong zone costs.
   */
  private static final Duration CLOSE_ENOUGH = Duration.ofMinutes(5);

  /**
   * How long a test waits for something it expects to happen. It guards against a machine
   * which leaves the JVM without a turn. It does not measure speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final TimeZone ZONE_OF_THE_BUILD = TimeZone.getDefault();

  /**
   * Started at the first test which needs it, and only where Docker runs.
   */
  private static PostgreSQLContainer<?> postgres;

  /**
   * A database the stores write into, and a way to open a connection to it.
   *
   * @param name What the test report calls it
   * @param connectionsOrNull Opens a new connection, under the zone which is the default at
   *          that moment. <code>null</code> where the database cannot run here
   */
  record Database(String name, JdbcConnectionAccess connectionsOrNull) {

    @Override
    public String toString() {

      return name;

    }

  }

  static Stream<Arguments> databasesAndZones() {

    final var h2 = new Database(
        "H2", () -> DriverManager
            .getConnection("jdbc:h2:mem:utc-timestamps;DB_CLOSE_DELAY=-1", "sa", ""));
    final var postgres = new Database("PostgreSQL", postgresConnections());
    return Stream
        .of(h2, postgres)
        .flatMap(database -> Stream
            .of(
                Arguments.of(database, KIRITIMATI, LOS_ANGELES),
                Arguments.of(database, LOS_ANGELES, KIRITIMATI)));

  }

  private static JdbcConnectionAccess postgresConnections() {

    return () -> {
      synchronized (TheStoresWriteTheirTimestampsInUtcTest.class) {
        if (postgres == null) {
          postgres = new PostgreSQLContainer<>("postgres:16-alpine");
          postgres.start();
        }
      }
      return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    };

  }

  @AfterAll
  static void stopPostgres() {

    if (postgres != null) {
      postgres.stop();
      postgres = null;
    }

  }

  @AfterEach
  void backToTheZoneOfTheBuild() {

    TimeZone.setDefault(ZONE_OF_THE_BUILD);

  }

  private static void assumeTheDatabaseRuns(
      final Database database) {

    if ("PostgreSQL".equals(database.name())) {
      Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "PostgreSQL needs Docker");
    }

  }

  private static void theJvmRunsIn(
      final String zone) {

    TimeZone.setDefault(TimeZone.getTimeZone(zone));

  }

  /**
   * A table of its own for every run. The runs share one database per product.
   */
  private static String aTableNamed(
      final String prefix) {

    return prefix + UUID
        .randomUUID()
        .toString()
        .replace("-", "")
        .substring(0, 12)
        .toUpperCase();

  }

  /**
   * The value as the database holds it, read as text, so that no zone of a driver can enter
   * it. The text of a <code>TIMESTAMP</code> is the same on both databases.
   */
  private static LocalDateTime rawValueOf(
      final JdbcConnectionAccess connections,
      final String select) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(select); var resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next(), "nothing was written: "
          + select);
      return LocalDateTime.parse(resultSet.getString(1).replace(' ', 'T'));
    }

  }

  private static void assertCloseTo(
      final LocalDateTime expected,
      final LocalDateTime actual,
      final String what) {

    final var apart = Duration.between(expected, actual).abs();
    assertTrue(
        apart.compareTo(CLOSE_ENOUGH) < 0, "%s: expected about %s (UTC), but the column holds %s"
            .formatted(what, expected, actual));

  }

  private static PhaseTwoOutboxProperties outboxProperties() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setPollInterval(Duration.ofMillis(200));
    properties.setAttemptFrequency(Duration.ofMillis(200));
    // the housekeeping deletes DONE entries in a window of the night. That window is shut
    // here, so only the test removes an entry
    final var now = java.time.LocalTime.now(java.time.ZoneId.systemDefault());
    properties.getHousekeeping().setStart(now.minusHours(3));
    properties.getHousekeeping().setEnd(now.minusHours(2));
    return properties;

  }

  private static PhaseTwoRouter aRouterWhichDispatches() {

    @SuppressWarnings("unchecked")
    final MigrationProcessService<Object> processService = Mockito.mock(MigrationProcessService.class);
    Mockito.when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    Mockito.when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    Mockito.when(processService.convertAggregateId(Mockito.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  /**
   * A transaction which is always running and commits at once. The connections of this test
   * commit every statement on their own.
   */
  private static final PhaseTwoOutboxTransaction ALWAYS_RUNNING = new PhaseTwoOutboxTransaction() {

    @Override
    public boolean isActive() {

      return true;

    }

    @Override
    public void afterCommit(
        final Runnable action) {

      action.run();

    }

  };

  /**
   * The outbox of one run: its dispatcher, which creates the tables, and its store.
   */
  private record Outbox(String table, JdbcPhaseTwoOutboxDispatcher dispatcher, JdbcPhaseTwoOutboxStore store) {

  }

  private static Outbox anOutboxOn(
      final JdbcConnectionAccess connections) {

    final var table = aTableNamed("OUTBOX_UTC_");
    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcPhaseTwoOutboxStore
            .entriesNamingTheirPayload(table));
    final var properties = outboxProperties();
    // a housekeeping table of its own as well. Its name is the one place where the runs
    // would meet otherwise
    properties.getJdbc().setHousekeepingTable(table
        + "_HK");
    final var dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, table, payloadStore, TheStoresWriteTheirTimestampsInUtcTest::aRouterWhichDispatches, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");
    dispatcher.prepareSchema();
    final var store = new JdbcPhaseTwoOutboxStore(connections, ALWAYS_RUNNING, table, payloadStore, () -> {
    });
    return new Outbox(table, dispatcher, store);

  }

  private static String statusOf(
      final JdbcConnectionAccess connections,
      final String table) throws SQLException {

    try (Connection connection = connections.acquire(); var statement = connection
        .prepareStatement("SELECT STATUS FROM %s".formatted(table)); var resultSet = statement.executeQuery()) {
      assertTrue(resultSet.next(), "the entry is gone");
      return resultSet.getString(1);
    }

  }

  private static void awaitDone(
      final JdbcConnectionAccess connections,
      final String table) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(statusOf(connections, table))) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the entry was never dispatched, so it was not due in the zone which read it");
      Thread.sleep(50);
    }

  }

  @ParameterizedTest(name = "{0}: written in {1}, read in {2}")
  @MethodSource("databasesAndZones")
  @DisplayName("An entry written in one zone is due at once in another, and its columns hold UTC")
  public void anEntryIsDueAtOnceWhateverTheZone(
      final Database database,
      final String writingZone,
      final String readingZone) throws Exception {

    assumeTheDatabaseRuns(database);
    final var connections = database.connectionsOrNull();

    theJvmRunsIn(writingZone);
    final var outbox = anOutboxOn(connections);
    assertTrue(
        outbox
            .store()
            .schedule(PhaseTwoCall.of(PhaseOperation.START_WORKFLOW, MODULE, PROCESS, "4711", "dummy", Map.of())),
        "the entry was not written");

    final var utcNow = LocalDateTime.now(ZoneOffset.UTC);
    assertCloseTo(
        utcNow, rawValueOf(connections, "SELECT CREATED_AT FROM %s".formatted(outbox.table())), "CREATED_AT");
    assertCloseTo(
        utcNow, rawValueOf(connections, "SELECT NEXT_ATTEMPT_AT FROM %s".formatted(outbox.table())), "NEXT_ATTEMPT_AT");

    theJvmRunsIn(readingZone);
    final var age = outbox
        .store()
        .ageOfOldestPendingCall()
        .orElseThrow();
    assertTrue(age.compareTo(CLOSE_ENOUGH) < 0, "the entry is a moment old, not "
        + age);

    try {
      outbox.dispatcher().start();
      awaitDone(connections, outbox.table());
    } finally {
      outbox.dispatcher().stop();
    }
    assertCloseTo(
        LocalDateTime.now(ZoneOffset.UTC), rawValueOf(connections, "SELECT DONE_AT FROM %s"
            .formatted(outbox.table())),
        "DONE_AT");

    // the retention: the entry was dispatched a moment ago, in the other zone
    theJvmRunsIn(writingZone);
    assertEquals(
        0, outbox
            .dispatcher()
            .removeDispatchedEntriesOlderThan(Instant.now().minus(Duration.ofHours(1)), 10),
        "an entry dispatched a moment ago is kept for a retention of an hour");
    assertEquals(
        1, outbox
            .dispatcher()
            .removeDispatchedEntriesOlderThan(Instant.now().plus(Duration.ofMinutes(1)), 10),
        "an entry dispatched before the threshold is removed");

  }

  @ParameterizedTest(name = "{0}: written in {1}, read in {2}")
  @MethodSource("databasesAndZones")
  @DisplayName("A delivery record written in one zone expires by its age in another, and its columns hold UTC")
  public void aDeliveryRecordExpiresByItsAgeWhateverTheZone(
      final Database database,
      final String writingZone,
      final String readingZone) throws Exception {

    assumeTheDatabaseRuns(database);
    final var connections = database.connectionsOrNull();
    final var table = aTableNamed("DELIVERY_UTC_");
    final var store = new JdbcTaskDeliveryStore(connections, table);

    theJvmRunsIn(writingZone);
    store.createSchemaIfNotExists();
    final var twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
    store.record(aCompletedDelivery("old", twoHoursAgo));
    store.record(aCompletedDelivery("young", Instant.now().minus(Duration.ofMinutes(30))));

    assertCloseTo(
        LocalDateTime.ofInstant(twoHoursAgo, ZoneOffset.UTC),
        rawValueOf(connections, "SELECT RECORDED_AT FROM %s WHERE DELIVERY_KEY = 'old'"
            .formatted(table)),
        "RECORDED_AT");

    theJvmRunsIn(readingZone);
    assertEquals(
        twoHoursAgo.toEpochMilli(), store
            .recordedDelivery("old")
            .orElseThrow()
            .recordedAt()
            .toEpochMilli(),
        "the moment read back is the moment written");
    assertEquals(1, store.deleteExpired(Duration.ofHours(1)), "only the record older than the retention expires");
    assertTrue(store.recordedDelivery("young").isPresent(), "the younger record stays");

  }

  private static TaskDelivery aCompletedDelivery(
      final String deliveryKey,
      final Instant recordedAt) {

    return new TaskDelivery(deliveryKey, "adapter", MODULE, PROCESS, "4711", null, "aTask", null, null, "COMPLETED", null, null, recordedAt, null);

  }

}
