package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * How old an open task is, and what VanillaBP does about it.
 * <p>
 * A task left open by a <code>&#64;TaskId</code> handler is answered from its delivery
 * record on every redelivery, which is what keeps the handler from running twice. The
 * record carries the moment the handler ran, so the core can measure how long the
 * application has owed that task its completion - the generic half, which holds for
 * every BPMS. What is pinned here:
 * <ul>
 * <li>a redelivery of an open task reports COMPLETION_PENDING and does not touch the
 * workflow aggregate;</li>
 * <li>the outcome carries the age, which is the distance to the recorded moment;</li>
 * <li>a task older than <code>vanillabp.delivery.max-task-age</code> is reported ONCE, by
 * a message naming the workflow module, the process, the aggregate and the age, and the
 * outcome says so - which is what a BPMS-specific reaction hangs on;</li>
 * <li>the maximum age resolves per workflow module, workflow and task, most specific
 * wins, and zero switches the report off.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTaskAgeTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  private static final String TASK = "asyncTask";

  public static class Aggregate {

    @Getter
    String id;

    int invocations;

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

  static class TransactionRunnerStub implements TransactionRunner {

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return requireNew(work);
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  /**
   * A delivery log in memory whose records may be BACKDATED - which is how a task open
   * for thirty days is tested without waiting for them.
   */
  static class InMemoryDeliveryLog implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    final List<String> recorded = new ArrayList<>();

    final List<String> stillOpen = new ArrayList<>();

    @Override
    public void stillOpen(
        final String deliveryKey) {
      stillOpen.add(deliveryKey);
    }

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {
      return Optional.ofNullable(records.get(deliveryKey));
    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {
      recorded.add(delivery.deliveryKey());
      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;
    }

    void backdateBy(
        final Duration age) {

      records
          .replaceAll(
              (
                  key,
                  delivery) -> new TaskDelivery(
                      delivery.deliveryKey(), delivery.adapterId(), delivery.workflowModuleId(), delivery
                          .bpmnProcessId(), delivery
                              .workflowAggregateId(), delivery.taskDefinition(), delivery.outcome(), delivery
                                  .bpmnErrorCode(), delivery.bpmnErrorName(), Instant.now().minus(age)));

    }

  }

  public static class TestWorkflowService {

    /**
     * A task the application completes later - the only kind which can be left open.
     */
    @WorkflowTask(taskDefinition = TASK)
    public void asyncTask(
        final Aggregate aggregate,
        @TaskId final String taskId) {

      aggregate.invocations++;

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final InMemoryDeliveryLog deliveryLog = new InMemoryDeliveryLog();

  /**
   * Properties configuring the maximum age at the given levels - <code>null</code> means
   * "not configured there", so the most-specific-wins resolution can be exercised.
   */
  private MigrationAdapterProperties properties(
      final Duration global,
      final Duration moduleLevel,
      final Duration workflowLevel,
      final Duration taskLevel) {

    final var task = TaskAdapterProperties
        .builder()
        .delivery(deliveryProperties(taskLevel))
        .build();

    final var workflow = WorkflowAdapterProperties
        .builder()
        .tasks(Map.of(TASK, task))
        .delivery(deliveryProperties(workflowLevel))
        .build();

    final var workflowModule = WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of(PROCESS, workflow))
        .delivery(deliveryProperties(moduleLevel))
        .build();

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, workflowModule))
        .delivery(deliveryProperties(global))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private static DeliveryProperties deliveryProperties(
      final Duration maxTaskAge) {

    if (maxTaskAge == null) {
      return null;
    }
    final var properties = new DeliveryProperties();
    properties.setMaxTaskAge(maxTaskAge);
    return properties;

  }

  private WorkflowTaskRegistry registry(
      final MigrationAdapterProperties properties) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(adapter.deliversTasksAtLeastOnce()).thenReturn(true);

    final TaskDeliveryLogResolver resolver = new TaskDeliveryLogResolver() {

      @Override
      public TaskDeliveryLog resolveFor(
          final Class<?> workflowAggregateClass) {
        return deliveryLog;
      }

      @Override
      public String remediesDescription() {
        return "- add a store, or";
      }

    };

    final var processService = new MigrationProcessService<>(
        MODULE, PROCESS, Aggregate.class, properties, persistence, List.of(adapter), null, null, resolver);

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry.registerWorkflowService(
        MODULE,
        PROCESS,
        TestWorkflowService.class,
        TestWorkflowService::new,
        type -> null,
        processService);
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  private TaskInvocationContext delivery(
      final String aggregateId,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return TASK;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getTaskId() {
        return deliveryId;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  @Test
  @DisplayName("A redelivery of an open task reports it as pending and leaves the aggregate alone")
  public void aRedeliveryOfAnOpenTaskDoesNotTouchTheAggregate() {

    final var testee = registry(properties(null, null, null, null));
    storeAggregate("4711");

    final var first = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4711", "job-1"));
    final var second = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4711", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, first.kind());
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, second.kind());
    assertEquals(1, persistence.aggregates.get("4711").invocations, "the handler ran exactly once");
    assertEquals(1, deliveryLog.recorded.size(), "the delivery was recorded once");
    assertNotNull(second.openFor(), "the redelivery knows how long the task has been open");
    assertFalse(second.maxAgeExceeded(), "a task open for milliseconds is not old");

  }

  @Test
  @DisplayName("A task older than the maximum age is reported exactly once, naming the age")
  public void anOverdueTaskIsReportedOnce() {

    final var testee = registry(properties(Duration.ofHours(1), null, null, null));
    storeAggregate("4712");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4712", "job-1"));
    deliveryLog.backdateBy(Duration.ofHours(3));

    final var messages = new ArrayList<String>();
    final var second = loggedBy(
        messages,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4712", "job-1")));

    assertTrue(second.maxAgeExceeded(), "the outcome says the task is overdue");
    assertTrue(second.openFor().toHours() >= 3, "the age is what the record says");
    assertEquals(1, messages.size(), "exactly one message");
    final var message = messages.getFirst();
    assertTrue(message.contains(MODULE), "names the workflow module");
    assertTrue(message.contains(PROCESS), "names the BPMN process");
    assertTrue(message.contains("4712"), "names the workflow aggregate");
    assertTrue(message.contains(TASK), "names the task");
    assertTrue(message.contains("PT3H"), "names the age");
    assertTrue(message.contains("vanillabp.delivery.max-task-age"), "names the property");

    // the lock of an open task is renewed by every redelivery - the report is about the
    // task, not about the renewal
    final var repeated = new ArrayList<String>();
    final var third = loggedBy(
        repeated,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4712", "job-1")));
    assertTrue(third.maxAgeExceeded(), "the task is still overdue");
    assertTrue(repeated.isEmpty(), "and it is not reported a second time");

  }

  @Test
  @DisplayName("The maximum age resolves per workflow module, workflow and task")
  public void theMaximumAgeResolvesMostSpecificFirst() {

    assertEquals(
        DeliveryProperties.DEFAULT_MAX_TASK_AGE,
        properties(null, null, null, null).maxTaskAge(MODULE, PROCESS, TASK),
        "nothing configured anywhere is thirty days");
    assertEquals(
        Duration.ofDays(1),
        properties(Duration.ofDays(1), null, null, null).maxTaskAge(MODULE, PROCESS, TASK));
    assertEquals(
        Duration.ofDays(2),
        properties(Duration.ofDays(1), Duration.ofDays(2), null, null).maxTaskAge(MODULE, PROCESS, TASK));
    assertEquals(
        Duration.ofDays(3),
        properties(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3), null)
            .maxTaskAge(MODULE, PROCESS, TASK));
    assertEquals(
        Duration.ofDays(4),
        properties(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3), Duration.ofDays(4))
            .maxTaskAge(MODULE, PROCESS, TASK));
    assertEquals(
        Duration.ofDays(2),
        properties(Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(3), Duration.ofDays(4))
            .maxTaskAge(MODULE, "AnotherProcess", TASK),
        "a workflow nobody configured falls back to its module");

  }

  @Test
  @DisplayName("A maximum age of zero switches the report off")
  public void zeroSwitchesTheReportOff() {

    final var testee = registry(properties(Duration.ZERO, null, null, null));
    storeAggregate("4713");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4713", "job-1"));
    deliveryLog.backdateBy(Duration.ofDays(400));

    final var messages = new ArrayList<String>();
    final var second = loggedBy(
        messages,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4713", "job-1")));

    assertFalse(second.maxAgeExceeded(), "nothing is overdue where no maximum applies");
    assertTrue(messages.isEmpty(), "and nothing is reported");
    assertNotNull(second.openFor(), "the age is still known - only the judgement is off");

  }

  @Test
  @DisplayName("A record written before the timestamp existed reports no age")
  public void aRecordWithoutATimestampReportsNoAge() {

    final var testee = registry(properties(Duration.ofHours(1), null, null, null));
    storeAggregate("4714");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4714", "job-1"));
    deliveryLog.records
        .replaceAll(
            (
                key,
                delivery) -> new TaskDelivery(
                    delivery.deliveryKey(), delivery.adapterId(), delivery.workflowModuleId(), delivery
                        .bpmnProcessId(), delivery
                            .workflowAggregateId(), delivery.taskDefinition(), delivery.outcome(), delivery
                                .bpmnErrorCode(), delivery.bpmnErrorName(), null));

    final var second = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4714", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, second.kind());
    assertEquals(null, second.openFor(), "an unknown age is not a zero age");
    assertFalse(second.maxAgeExceeded());

  }

  @Test
  @DisplayName("A negative maximum age fails the startup naming the property")
  public void aNegativeMaximumAgeFailsTheStartup() {

    final var properties = properties(Duration.ofDays(1), Duration.ofDays(-1), null, null);

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> properties.validateProperties(List.of("dummy"), List.of(MODULE)));

    assertTrue(
        exception.getMessage().contains("vanillabp.workflow-modules.test-module.delivery.max-task-age"),
        "names the level which carries the value: "
            + exception.getMessage());
    assertTrue(exception.getMessage().contains("P30D"), "names the default");

  }

  /**
   * Runs the given work and collects the warnings the core emitted while it ran.
   */
  private WorkflowTaskOutcome loggedBy(
      final List<String> messages,
      final Supplier<WorkflowTaskOutcome> work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(MigrationProcessService.class);
    logger.addAppender(logWatcher);
    try {
      return work.get();
    } finally {
      logger.detachAndStopAllAppenders();
      logWatcher.list
          .stream()
          .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .forEach(messages::add);
    }

  }

  @Test
  @DisplayName("Every redelivery tells the store the record is still in use, and the age stays put")
  public void aRedeliveryKeepsTheRecordAliveWithoutMovingTheAge() {

    final var testee = registry(properties(Duration.ofHours(1), null, null, null));
    storeAggregate("4715");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4715", "job-1"));
    assertTrue(deliveryLog.stillOpen.isEmpty(), "the delivery which ran the handler is no redelivery");

    deliveryLog.backdateBy(Duration.ofHours(3));
    final var second = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4715", "job-1"));
    final var third = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4715", "job-1"));

    assertEquals(
        2,
        deliveryLog.stillOpen.size(),
        "every redelivery of the open task reaches the store, which is what keeps its record");
    assertEquals(deliveryLog.recorded.getFirst(), deliveryLog.stillOpen.getFirst(), "under the recorded key");
    assertEquals(deliveryLog.recorded.getFirst(), deliveryLog.stillOpen.getLast());
    assertTrue(second.openFor().toHours() >= 3, "and the age keeps counting from the moment the handler ran");
    assertTrue(third.openFor().toHours() >= 3);
    assertTrue(third.maxAgeExceeded(), "so the maximum age still fires");

  }

}
