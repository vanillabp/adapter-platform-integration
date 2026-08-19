package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;

/**
 * Story 76: a workflow which ended releases the records of its processed task deliveries,
 * so the deduplication window is closed by the workflow instead of by the clock. What is
 * pinned here is the CORE's part of it:
 * <ul>
 * <li>the deletion runs when a workflow ends, bounded by workflow module, BPMN process,
 * aggregate and by the moment of the notification - the records of another workflow, of
 * another process, of another module and of a SECOND workflow on the same aggregate are
 * untouched;</li>
 * <li>it runs without a <code>&#64;WorkflowEnded</code> method as well, which is why the
 * core asks the adapters to attach their listener where the release is switched on;</li>
 * <li>with the option off nothing is released and no listener is wanted;</li>
 * <li>a store which does not implement the release plus the option on is reported at
 * startup, naming the store and the property.</li>
 * </ul>
 * The stores themselves (JDBC, MongoDB) are covered by the platform integrations' tests -
 * per platform, as the coverage rules require.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryRecordReleaseTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  public static class Aggregate {

    String id;

    String status;

    public String getId() {
      return id;
    }

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  /**
   * A delivery log in memory which really filters: the release has to be bounded the way
   * a store would bound its DELETE, otherwise this test would pin nothing.
   */
  static class InMemoryDeliveryLog implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new LinkedHashMap<>();

    final Map<String, Instant> recordedAt = new HashMap<>();

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {
      return Optional.ofNullable(records.get(deliveryKey));
    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {
      recordedAt.put(delivery.deliveryKey(), Instant.now());
      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;
    }

    @Override
    public int releaseRecordsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final Instant recordedBefore) {

      final var released = new LinkedList<String>();
      records
          .forEach((
              key,
              delivery) -> {
            if (!delivery.workflowModuleId().equals(workflowModuleId)) {
              return;
            }
            if (!delivery.bpmnProcessId().equals(bpmnProcessId)) {
              return;
            }
            if (!delivery.workflowAggregateId().equals(workflowAggregateId)) {
              return;
            }
            if (!recordedAt.get(key).isBefore(recordedBefore)) {
              return;
            }
            released.add(key);
          });
      released.forEach(records::remove);
      released.forEach(recordedAt::remove);
      return released.size();

    }

    /**
     * Writes a record as a processed delivery would have, at a moment this test decides -
     * the bound of the release is a timestamp, so it has to be one the test controls.
     */
    void given(
        final String key,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String aggregateId,
        final Instant when) {

      records
          .put(
              key,
              new TaskDelivery(
                  key, workflowModuleId, bpmnProcessId, aggregateId, "task", "COMPLETED", null, null));
      recordedAt.put(key, when);

    }

  }

  /**
   * A store written before the release existed: it inherits the SPI's default doing
   * nothing.
   */
  static class LegacyDeliveryLog implements TaskDeliveryLog {

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {
      return Optional.empty();
    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {
      return true;
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  /**
   * A workflow service which does NOT want to know about the end of its workflows - the
   * case the release has to work in, since it is what makes the listener necessary.
   */
  public static class SilentService {

  }

  public static class NoticingService {

    static int calls;

    @WorkflowEnded
    public void ended(
        final Aggregate aggregate) {

      calls++;
      aggregate.status = "ended";

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final InMemoryDeliveryLog deliveryLog = new InMemoryDeliveryLog();

  /**
   * The properties of an application which configures the release globally, per workflow
   * module, or not at all (<code>null</code> = nothing configured at that level).
   */
  private MigrationAdapterProperties properties(
      final Boolean global,
      final Boolean module) {

    final var moduleProperties = WorkflowModuleAdapterProperties.builder();
    if (module != null) {
      final var moduleDelivery = new DeliveryProperties();
      moduleDelivery.setReleaseOnWorkflowEnd(module);
      moduleProperties.delivery(moduleDelivery);
    }

    final var globalDelivery = new DeliveryProperties();
    globalDelivery.setReleaseOnWorkflowEnd(global);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, moduleProperties.build()))
        .delivery(globalDelivery)
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Aggregate> processService(
      final MigrationAdapterProperties properties,
      final TaskDeliveryLog log) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(adapter.deliversTasksAtLeastOnce()).thenReturn(true);

    final TaskDeliveryLogResolver resolver = new TaskDeliveryLogResolver() {

      @Override
      public TaskDeliveryLog resolveFor(
          final Class<?> workflowAggregateClass) {
        return log;
      }

      @Override
      public String remediesDescription() {
        return "- add a store, or";
      }

    };

    return new MigrationProcessService<>(
        MODULE, PROCESS, Aggregate.class, properties, persistence, List.of(adapter), null, null, resolver);

  }

  private WorkflowTaskRegistry registry(
      final MigrationAdapterProperties properties,
      final Class<?> workflowServiceClass,
      final Supplier<Object> bean) {

    final var registry = new WorkflowTaskRegistry(
        new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            workflowServiceClass,
            bean,
            type -> null,
            processService(properties, deliveryLog));
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    aggregate.status = "running";
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  private WorkflowEndedContext context(
      final String aggregateId) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

      @Override
      public String getEndEventId() {
        return "Event_Done";
      }

      @Override
      public boolean runInCurrentTransaction() {
        return false;
      }

    };

  }

  /**
   * The messages the given class logged at WARN or above while the work ran.
   */
  private List<String> warningsOf(
      final Class<?> loggingClass,
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggingClass);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
        .map(event -> event.getFormattedMessage())
        .toList();

  }

  @Test
  @DisplayName("An ended workflow releases its records - and only its own")
  public void theEndOfAWorkflowReleasesItsRecords() {

    final var testee = registry(properties(true, null), SilentService.class, SilentService::new);
    storeAggregate("4711");

    final var past = Instant.now().minus(1, ChronoUnit.MINUTES);
    deliveryLog.given("of-the-workflow-1", MODULE, PROCESS, "4711", past);
    deliveryLog.given("of-the-workflow-2", MODULE, PROCESS, "4711", past);
    deliveryLog.given("of-another-aggregate", MODULE, PROCESS, "4712", past);
    deliveryLog.given("of-another-process", MODULE, "OtherProcess", "4711", past);
    deliveryLog.given("of-another-module", "other-module", PROCESS, "4711", past);
    // the second workflow on the same aggregate: its record is written AFTER the end of
    // the first one, which is exactly what the time bound protects
    deliveryLog
        .given("of-the-second-workflow", MODULE, PROCESS, "4711", Instant.now().plus(1, ChronoUnit.MINUTES));

    testee.workflowEnded(MODULE, PROCESS, context("4711"));

    assertEquals(
        List.of("of-another-aggregate", "of-another-process", "of-another-module", "of-the-second-workflow"),
        List.copyOf(deliveryLog.records.keySet()),
        "only the records of the ended workflow, written before its end, may be released");

  }

  @Test
  @DisplayName("Where the release is switched on the end has to be reported, even without a @WorkflowEnded method")
  public void theListenerIsWantedWhereTheReleaseIsSwitchedOn() {

    final var withRelease = registry(properties(true, null), SilentService.class, SilentService::new);

    assertTrue(
        withRelease.workflowEndedHandlerExists(MODULE, PROCESS),
        "the release needs the end notification, so the adapter has to attach its listener");

  }

  @Test
  @DisplayName("With the option off nothing is released and no listener is wanted")
  public void withoutTheOptionTheRecordsStay() {

    final var testee = registry(properties(false, null), SilentService.class, SilentService::new);
    storeAggregate("4711");
    deliveryLog.given("of-the-workflow", MODULE, PROCESS, "4711", Instant.now().minus(1, ChronoUnit.MINUTES));

    assertFalse(
        testee.workflowEndedHandlerExists(MODULE, PROCESS),
        "a model must not pay for a listener nobody uses");

    testee.workflowEnded(MODULE, PROCESS, context("4711"));

    assertEquals(
        List.of("of-the-workflow"),
        List.copyOf(deliveryLog.records.keySet()),
        "without the option the retention is what cleans up");

  }

  @Test
  @DisplayName("The workflow module's setting wins over the global one")
  public void theModulesSettingWins() {

    final var testee = registry(properties(false, true), SilentService.class, SilentService::new);
    storeAggregate("4711");
    deliveryLog.given("of-the-workflow", MODULE, PROCESS, "4711", Instant.now().minus(1, ChronoUnit.MINUTES));

    testee.workflowEnded(MODULE, PROCESS, context("4711"));

    assertTrue(deliveryLog.records.isEmpty(), "the module releases although the application does not");

  }

  @Test
  @DisplayName("The application's @WorkflowEnded method runs and the records are released in the same notification")
  public void theHandlerRunsAndTheRecordsAreReleased() {

    NoticingService.calls = 0;
    final var testee = registry(properties(true, null), NoticingService.class, NoticingService::new);
    final var aggregate = storeAggregate("4711");
    deliveryLog.given("of-the-workflow", MODULE, PROCESS, "4711", Instant.now().minus(1, ChronoUnit.MINUTES));

    testee.workflowEnded(MODULE, PROCESS, context("4711"));

    assertEquals(1, NoticingService.calls);
    assertEquals("ended", persistence.aggregates.get(aggregate.id).status);
    assertTrue(deliveryLog.records.isEmpty());

  }

  @Test
  @DisplayName("A store which cannot release plus the option on is reported at startup")
  public void aStoreWithoutTheReleaseIsReported() {

    final var properties = properties(true, null);
    final var processService = processService(properties, new LegacyDeliveryLog());

    final var messages = warningsOf(
        MigrationProcessService.class,
        processService::validateTaskDeliveryLogAtStartup);

    final var message = messages
        .stream()
        .filter(candidate -> candidate.contains("releaseRecordsOf"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no warning about the missing release, logged: "
            + messages));
    assertTrue(message.contains(LegacyDeliveryLog.class.getName()), message);
    assertTrue(message.contains("vanillabp.workflow-modules.test-module.delivery.release-on-workflow-end"), message);

  }

  @Test
  @DisplayName("With the option off a store which cannot release is not reported")
  public void aStoreWithoutTheReleaseIsSilentWhereNobodyAskedForIt() {

    final var processService = processService(properties(false, null), new LegacyDeliveryLog());

    final var messages = warningsOf(
        MigrationProcessService.class,
        processService::validateTaskDeliveryLogAtStartup);

    assertTrue(
        messages.stream().noneMatch(message -> message.contains("releaseRecordsOf")),
        "an application which did not ask for the release must not be told about it, logged: "
            + messages);

  }

}
