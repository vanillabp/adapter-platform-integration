package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
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
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * A BPMS repeating a delivery must not run the business code twice. What is
 * pinned here is the CORE's part of it - which delivery is remembered, what a repeated
 * one is answered with, and when nothing is remembered at all:
 * <ul>
 * <li>a repeated delivery skips the handler and reports the recorded outcome again
 * (completed as well as BPMN error, whose code and name have to survive);</li>
 * <li>a delivery whose handler threw leaves no record - the retry runs it, which is what
 * makes the BPMS' retry work;</li>
 * <li>nothing is remembered where the adapter reports no delivery identity, or where the
 * feature is switched off for the adapter, the workflow or the single task;</li>
 * <li>an adapter which may repeat deliveries without a log to remember them is reported
 * at startup, naming the property to silence it;</li>
 * <li>the elements of a multi-instance activity are told apart although they share task,
 * workflow and aggregate - the delivery id an adapter reports per activation is what
 * carries it, at any nesting depth, and a called process brings its own BPMN process id
 * on top.</li>
 * </ul>
 * The stores themselves (JDBC, MongoDB) are covered by the platform integrations' tests -
 * per platform, as the coverage rules require.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InboundIdempotencyTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  private static final String TASK = "task";

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

  /**
   * A transaction which really rolls back: everything recorded and saved while the work
   * ran is discarded when it throws - the only way to tell "no record was written"
   * apart from "the record was written and ignored".
   */
  static class TransactionRunnerStub implements TransactionRunner {

    private final Runnable rollback;

    TransactionRunnerStub(
        final Runnable rollback) {
      this.rollback = rollback;
    }

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      try {
        return work.get();
      } catch (final RuntimeException e) {
        rollback.run();
        throw e;
      }
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
   * A delivery log in memory, behaving like a store with a unique key.
   */
  static class InMemoryDeliveryLog implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    final List<String> recorded = new ArrayList<>();

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

  }

  public static class TestWorkflowService {

    /**
     * How often a handler really ran - counted outside the aggregate, since a
     * rolled-back delivery takes the aggregate's changes with it.
     */
    static int handlerCalls;

    @WorkflowTask(taskDefinition = TASK)
    public void task(
        final Aggregate aggregate) {

      handlerCalls++;
      aggregate.invocations++;

    }

    @WorkflowTask(taskDefinition = "failing")
    public void failing(
        final Aggregate aggregate) {

      handlerCalls++;
      aggregate.invocations++;
      throw new IllegalStateException("something broke");

    }

    @WorkflowTask(taskDefinition = "erroneous")
    public void erroneous(
        final Aggregate aggregate) throws TaskException {

      handlerCalls++;
      aggregate.invocations++;
      throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final InMemoryDeliveryLog deliveryLog = new InMemoryDeliveryLog();

  /**
   * Properties where the given switch values are configured - <code>null</code> means
   * "not configured at that level", so the resolution order of the key can be exercised.
   */
  private MigrationAdapterProperties properties(
      final Boolean adapterLevel,
      final Boolean workflowLevel,
      final Boolean taskLevel) {

    final var adapterConfig = AdapterConfigProperties.ofType("dummy");
    adapterConfig.setDeduplicateDeliveries(adapterLevel);

    final var taskAdapter = new AdapterProperties();
    taskAdapter.setDeduplicateDeliveries(taskLevel);
    final var task = TaskAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, taskAdapter))
        .build();

    final var workflowAdapter = new AdapterProperties();
    workflowAdapter.setDeduplicateDeliveries(workflowLevel);
    final var workflow = WorkflowAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, workflowAdapter))
        .tasks(Map.of(TASK, task))
        .build();

    final var workflowModule = WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of(PROCESS, workflow))
        .build();

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, adapterConfig))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, workflowModule))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Aggregate> processService(
      final MigrationAdapterProperties properties,
      final TaskDeliveryLog log) {

    return processService(properties, log, PROCESS);

  }

  /**
   * A process service of another BPMN process of the same workflow module - what a
   * called process is: a secondary workflow of the SAME aggregate, delivering its tasks
   * under its own BPMN process id.
   */
  private MigrationProcessService<Aggregate> processService(
      final MigrationAdapterProperties properties,
      final TaskDeliveryLog log,
      final String bpmnProcessId) {

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
        MODULE, bpmnProcessId, Aggregate.class, properties, persistence, List.of(adapter), null, null, resolver);

  }

  private WorkflowTaskRegistry registry(
      final MigrationProcessService<Aggregate> processService) {

    final var registry = new WorkflowTaskRegistry(
        new TransactionRunnerStub(() -> {
          // the rolled-back transaction takes the delivery record AND the aggregate
          // changes with it
          deliveryLog.records.clear();
          persistence.aggregates.values().forEach(aggregate -> aggregate.invocations = 0);
        }));
    registry.registerWorkflowService(
        MODULE,
        PROCESS,
        TestWorkflowService.class,
        TestWorkflowService::new,
        type -> null,
        processService);
    return registry;

  }

  private void register(
      final WorkflowTaskRegistry registry,
      final String bpmnProcessId,
      final MigrationProcessService<Aggregate> processService) {

    registry.registerWorkflowService(
        MODULE,
        bpmnProcessId,
        TestWorkflowService.class,
        TestWorkflowService::new,
        type -> null,
        processService);

  }

  private Aggregate storeAggregate(
      final String id) {

    TestWorkflowService.handlerCalls = 0;
    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  @Test
  @DisplayName("A repeated delivery runs the handler once and reports the same outcome twice")
  public void repeatedDeliveryIsAnsweredFromTheRecord() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4711");

    final var first = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4711", "job-1"));
    final var second = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4711", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, first.kind());
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, second.kind());
    assertEquals(1, persistence.aggregates.get("4711").invocations, "the handler ran exactly once");
    assertEquals(1, deliveryLog.recorded.size(), "the delivery was recorded once");

    // a genuinely new task instance of the same workflow is a different delivery
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4711", "job-2"));
    assertEquals(2, persistence.aggregates.get("4711").invocations);

  }

  @Test
  @DisplayName("A repeated delivery of a BPMN error reports code and name again")
  public void repeatedDeliveryReportsTheRecordedBpmnError() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4712");

    final var first = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4712", "job-1"));
    final var second = testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4712", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, second.kind());
    assertEquals(first.errorCode(), second.errorCode());
    assertEquals("PAYMENT_FAILED", second.errorCode());
    assertEquals("PaymentFailed", second.errorName());
    assertEquals(1, persistence.aggregates.get("4712").invocations, "the handler ran exactly once");

  }

  @Test
  @DisplayName("A delivery whose handler threw leaves no record - the retry runs it again")
  public void aRolledBackDeliveryIsProcessedAgain() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4713");

    assertThrows(
        IllegalStateException.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("failing", "4713", "job-1")));
    assertTrue(
        deliveryLog.records.isEmpty(),
        "a handler which threw has no outcome to remember, and the rollback would take a record with it");

    // the retry of the very same delivery reaches the handler
    assertThrows(
        IllegalStateException.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("failing", "4713", "job-1")));
    assertEquals(2, TestWorkflowService.handlerCalls, "the handler ran for both attempts");

  }

  @Test
  @DisplayName("Without a delivery identity nothing is remembered")
  public void deliveriesWithoutAnIdentityAreNotDeduplicated() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4714");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4714", null));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4714", null));

    assertEquals(2, persistence.aggregates.get("4714").invocations);
    assertTrue(deliveryLog.records.isEmpty());

  }

  @Test
  @DisplayName("The switch is resolved per task: workflow on, task off")
  public void theSwitchIsResolvedTaskBeforeWorkflowBeforeAdapter() {

    // adapter level off, workflow level on, task level off again - the most specific
    // value wins, so THIS task is not deduplicated
    final var testee = registry(processService(properties(false, true, false), deliveryLog));
    storeAggregate("4715");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4715", "job-1"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4715", "job-1"));
    assertEquals(2, persistence.aggregates.get("4715").invocations);

    // another task of the same workflow inherits the workflow's ON
    storeAggregate("4716");
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4716", "job-2"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4716", "job-2"));
    assertEquals(1, persistence.aggregates.get("4716").invocations);

  }

  @Test
  @DisplayName("An adapter repeating deliveries without a log is reported at startup")
  public void aMissingDeliveryLogIsReportedAtStartup() {

    final var testee = processService(properties(null, null, null), null);

    final var messages = loggedBy(MigrationProcessService.class, testee::validateTaskDeliveryLogAtStartup);

    assertEquals(1, messages.size());
    final var message = messages.getFirst();
    assertTrue(message.contains(ADAPTER), "names the adapter");
    assertTrue(message.contains(PROCESS), "names the BPMN process");
    assertTrue(message.contains("TaskDeliveryLog"), "names the SPI to implement");
    assertTrue(
        message.contains("vanillabp.adapters.test-adapter.deduplicate-deliveries"),
        "names the property to state that the handlers are idempotent themselves");

    // the message names a configuration gap - it must not be repeated per delivery
    assertTrue(
        loggedBy(MigrationProcessService.class, testee::validateTaskDeliveryLogAtStartup).isEmpty());

  }

  @Test
  @DisplayName("Nothing is reported where no adapter can repeat a delivery")
  public void anEmbeddedBpmsNeedsNoDeliveryLog() {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> embedded = mock(MigratableProcessService.class);
    lenient().when(embedded.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(embedded.deliversTasksAtLeastOnce()).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        MODULE, PROCESS, Aggregate.class, properties(null, null, null), persistence, List
            .of(embedded), null, null, null);

    assertTrue(loggedBy(MigrationProcessService.class, testee::validateTaskDeliveryLogAtStartup).isEmpty());

  }

  @Test
  @DisplayName("A record carrying an unknown outcome is processed again")
  public void anUnknownRecordedOutcomeFallsBackToProcessing() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4717");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4717", "job-1"));
    final var key = deliveryLog.recorded.getFirst();
    final var recorded = deliveryLog.records.get(key);
    deliveryLog.records.put(
        key,
        new TaskDelivery(
            key, recorded.adapterId(), recorded.workflowModuleId(), recorded.bpmnProcessId(), recorded
                .workflowAggregateId(), recorded.taskDefinition(), "WHATEVER", null, null, recorded
                    .recordedAt()));

    final var messages = loggedBy(
        MigrationProcessService.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4717", "job-1")));

    assertEquals(2, persistence.aggregates.get("4717").invocations, "the handler ran again");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("WHATEVER")),
        "the unknown outcome is named");

  }

  /**
   * The messages a logger emitted while the given work ran - the guiding messages are
   * WARNings, and "normal" logging is switched off during tests.
   */
  private List<String> loggedBy(
      final Class<?> loggingClass,
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggingClass);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }


  @Test
  @DisplayName("Every element of a multi-instance activity runs its own handler")
  public void multiInstanceElementsAreToldApart() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4718");

    // one task of one workflow, three activations: the adapter reports a delivery id per
    // activation (Camunda 8 the job key, the Process-Engine-API the task id), which is
    // the whole reason no multi-instance index has to enter the key. Sequential and
    // parallel multi-instance look exactly the same from here
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-1"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-2"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-3"));

    assertEquals(3, persistence.aggregates.get("4718").invocations, "every element ran its handler");
    assertEquals(3, deliveryLog.records.size(), "every element got its own record");

    // and a redelivery of ONE element is still answered from that element's record
    final var redelivered = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-2"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, redelivered.kind());
    assertEquals(3, persistence.aggregates.get("4718").invocations, "the redelivery ran no handler");

  }

  @Test
  @DisplayName("A called process is told apart even where the BPMS repeats a delivery id")
  public void aCalledProcessBringsItsOwnBpmnProcessId() {

    final var properties = properties(null, null, null);
    final var testee = registry(processService(properties, deliveryLog));
    // the called process of a multi-instance call activity: a secondary workflow of the
    // same aggregate, so nothing but the BPMN process id and the delivery id tells its
    // tasks apart from the caller's. The argument counts no levels, which is why any
    // nesting depth is covered by this one
    register(testee, "CalledProcess", processService(properties, deliveryLog, "CalledProcess"));
    storeAggregate("4719");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4719", "job-1"));
    testee.invokeWorkflowTask(MODULE, "CalledProcess", delivery(TASK, "4719", "job-1"));

    assertEquals(2, persistence.aggregates.get("4719").invocations, "both handlers ran");
    assertEquals(2, deliveryLog.records.size(), "the two deliveries are two records");

  }

}
