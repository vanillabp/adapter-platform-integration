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
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.extension.spi.resolver.TaskDeliveryLogResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * A BPMS which repeats a delivery must not run the business code twice. This pins the part
 * of the CORE: which delivery is remembered, what a repeated one is answered with, and when
 * nothing is remembered at all.
 * <ul>
 * <li>A repeated delivery skips the handler and reports the recorded outcome again. This
 * holds for completed and for BPMN error, whose code and name have to survive.</li>
 * <li>A delivery whose handler threw leaves no record. The retry runs it, and that makes the
 * retry of the BPMS work.</li>
 * <li>A delivery whose adapter reports no identity is still remembered, under a key which
 * recognises no repetition. The row answers which BPMS holds the task and which kind of id
 * its id is. Only the switch turns that off too.</li>
 * <li>Nothing is remembered where the feature is switched off for the adapter, the workflow
 * or the single task.</li>
 * <li>An adapter which may repeat deliveries without a log to remember them is reported at
 * startup. The report names the property which silences it.</li>
 * <li>The elements of a multi-instance activity are told apart, although they share task,
 * workflow and aggregate. The delivery id which an adapter reports per activation carries
 * the difference, at any nesting depth. A called process adds its own BPMN process id.</li>
 * </ul>
 * The platform integrations cover the stores themselves (JDBC, MongoDB) in their tests,
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
   * A transaction which really rolls back. Everything recorded and saved while the work ran
   * is discarded when it throws. Only so can the test tell "no record was written" apart
   * from "the record was written and ignored".
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
   * A delivery log in memory which behaves like a store with a unique key.
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
     * How often a handler really ran. It is counted outside the aggregate, because a
     * delivery which is rolled back takes the changes of the aggregate with it.
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
   * Properties with the given values of the switch. <code>null</code> means "not
   * configured at that level". So the test can exercise the order in which the key is
   * resolved.
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
   * A process service of another BPMN process of the same workflow module. A called process
   * is just that: a secondary workflow of the SAME aggregate, which delivers its tasks under
   * its own BPMN process id.
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

    return MigrationProcessService
        .forBpmnProcess(MODULE, bpmnProcessId, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .taskDeliveryLogResolver(resolver)
        .build();

  }

  private WorkflowTaskRegistry registry(
      final MigrationProcessService<Aggregate> processService) {

    final var registry = new WorkflowTaskRegistry(
        new TransactionRunnerStub(() -> {
          // the transaction which is rolled back takes the delivery record AND the changes
          // of the aggregate with it
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

    return delivery(taskDefinition, aggregateId, deliveryId, null);

  }

  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId,
      final String workflowId) {

    return new TaskInvocationContext() {

      @Override
      public String getWorkflowId() {
        return workflowId;
      }

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

    // a really new task instance of the same workflow is a different delivery
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4711", "job-2"));
    assertEquals(2, persistence.aggregates.get("4711").invocations);

  }

  @Test
  @DisplayName("A record of another aggregate under the same key fails the delivery instead of skipping it")
  public void aRecordOfAnotherAggregateIsNoRepeatedDelivery() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4720");
    storeAggregate("4721");

    // a BPMS whose database was reset hands out the ids it handed out before. So the record
    // which the key finds was written for a different workflow aggregate
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4720", "job-1"));

    final var messages = loggedBy(
        DeliveryRecords.class,
        () -> assertThrows(
            IllegalStateException.class,
            () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4721", "job-1"))));

    assertEquals(1, TestWorkflowService.handlerCalls, "the handler did not run for the second aggregate");
    assertEquals(1, deliveryLog.recorded.size(), "the failed delivery wrote no record");
    assertEquals(1, messages.size(), "the collision is said once per delivery: "
        + messages);
    final var message = messages.getFirst();
    assertTrue(message.contains("'4720'") && message.contains("'4721'"), "names both aggregates: "
        + message);
    assertTrue(message.contains("test-adapter|test-module|TestProcess|CREATED|job-1"), "names the key: "
        + message);
    assertTrue(message.contains("reset"), "names the likely cause: "
        + message);
    assertTrue(message.contains("AGGREGATE_ID"), "says how to find the old records: "
        + message);

  }

  @Test
  @DisplayName("A record of another workflow of the same aggregate under the same key fails the delivery")
  public void aRecordOfAnotherWorkflowOfTheSameAggregateIsNoRepeatedDelivery() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4722");

    // the aggregate started a second workflow after the BPMS was reset, and the new BPMS
    // handed the old job key to a task of that second workflow. Only the workflow id tells
    // the two deliveries apart
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4722", "job-1", "workflow-1"));
    final var repeated = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4722", "job-1", "workflow-1"));
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, repeated.kind());
    assertEquals(1, TestWorkflowService.handlerCalls, "a repetition of the same workflow is still skipped");

    final var messages = loggedBy(
        DeliveryRecords.class,
        () -> assertThrows(
            IllegalStateException.class,
            () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4722", "job-1", "workflow-2"))));

    assertEquals(1, TestWorkflowService.handlerCalls, "the handler did not run for the second workflow");
    assertEquals(1, deliveryLog.recorded.size(), "the failed delivery wrote no record");
    assertEquals(1, messages.size(), "the collision is said once per delivery: "
        + messages);
    final var message = messages.getFirst();
    assertTrue(message.contains("ANOTHER workflow 'workflow-1' of the same workflow aggregate"),
        "names the old workflow: "
            + message);
    assertTrue(message.contains("belongs to workflow 'workflow-2'"), "names the new workflow: "
        + message);
    assertTrue(message.contains("WORKFLOW_ID 'workflow-1'"), "says how to find the old records: "
        + message);

  }

  @Test
  @DisplayName("A delivery which names no workflow is compared by its aggregate only")
  public void aDeliveryWithoutAWorkflowIdIsComparedByItsAggregateOnly() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4723");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4723", "job-1", "workflow-1"));
    final var repeated = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4723", "job-1", null));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, repeated.kind());
    assertEquals(1, TestWorkflowService.handlerCalls, "a missing workflow id says nothing, so this is a repetition");

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
  @DisplayName("Without a delivery identity each delivery writes a row of its own")
  public void deliveriesWithoutAnIdentityAreNotDeduplicated() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4714");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4714", null));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4714", null));

    assertEquals(2, persistence.aggregates.get("4714").invocations, "nothing is deduplicated");
    assertEquals(2, deliveryLog.records.size(), "and both deliveries are written down");
    deliveryLog.records
        .keySet()
        .forEach(key -> assertTrue(
            key.contains("(not-deduplicated)"),
            "the key says that no repetition is recognised by it: "
                + key));
    deliveryLog.records
        .values()
        .forEach(record -> assertEquals(ADAPTER, record.adapterId(), "and the row still names the adapter"));

  }

  @Test
  @DisplayName("The switch turned off is the one case which writes nothing")
  public void nothingIsRememberedWhereTheSwitchIsOff() {

    // a BPMS behind this switch may repeat a delivery. A row per repetition would show the
    // same task as many times as it was handed out
    final var testee = registry(processService(properties(false, null, null), deliveryLog));
    storeAggregate("4717");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4717", "job-1"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4717", "job-1"));

    assertEquals(2, persistence.aggregates.get("4717").invocations);
    assertTrue(deliveryLog.records.isEmpty());

  }

  @Test
  @DisplayName("The switch is resolved per task: workflow on, task off")
  public void theSwitchIsResolvedTaskBeforeWorkflowBeforeAdapter() {

    // adapter level off, workflow level on, task level off again. The most specific value
    // wins, so THIS task is not deduplicated
    final var testee = registry(processService(properties(false, true, false), deliveryLog));
    storeAggregate("4715");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4715", "job-1"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4715", "job-1"));
    assertEquals(2, persistence.aggregates.get("4715").invocations);

    // another task of the same workflow inherits the ON of the workflow
    storeAggregate("4716");
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4716", "job-2"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("erroneous", "4716", "job-2"));
    assertEquals(1, persistence.aggregates.get("4716").invocations);

  }

  @Test
  @DisplayName("An adapter repeating deliveries without a log is reported at startup")
  public void aMissingDeliveryLogIsReportedAtStartup() {

    final var properties = properties(null, null, null);
    final var testee = processService(properties, null);

    final var messages = reportedWhile(properties, testee::validateTaskDeliveryLogAtStartup);

    assertEquals(1, messages.size());
    final var message = messages.getFirst();
    assertTrue(message.contains(ADAPTER), "names the adapter");
    assertTrue(message.contains(PROCESS), "names the BPMN process");
    assertTrue(message.contains("TaskDeliveryLog"), "names the SPI to implement");
    assertTrue(
        message.contains("vanillabp.adapters.test-adapter.deduplicate-deliveries"),
        "names the property to state that the handlers are idempotent themselves");

    // the message names a gap in the configuration. It must not be repeated per delivery
    assertEquals(
        messages,
        reportedWhile(properties, testee::validateTaskDeliveryLogAtStartup),
        "the same gap is not reported a second time");

  }

  @Test
  @DisplayName("Nothing is reported where no adapter can repeat a delivery")
  public void anEmbeddedBpmsNeedsNoDeliveryLog() {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> embedded = mock(MigratableProcessService.class);
    lenient().when(embedded.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(embedded.deliversTasksAtLeastOnce()).thenReturn(false);

    final var properties = properties(null, null, null);
    final var testee = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List
            .of(embedded))
        .build();

    assertTrue(reportedWhile(properties, testee::validateTaskDeliveryLogAtStartup).isEmpty());

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
        new TaskDelivery(key, recorded.adapterId(), recorded.workflowModuleId(), recorded.bpmnProcessId(), recorded
            .workflowAggregateId(), null, recorded.taskDefinition(), null, recorded
                .taskId(), "WHATEVER", null, null, recorded
                    .recordedAt(), null));

    final var messages = loggedBy(
        DeliveryRecords.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4717", "job-1")));

    assertEquals(2, persistence.aggregates.get("4717").invocations, "the handler ran again");
    assertTrue(
        messages.stream().anyMatch(message -> message.contains("WHATEVER")),
        "the unknown outcome is named");

  }

  /**
   * The messages a logger emitted while the given work ran. The guiding messages are
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

  /**
   * What the checks of the start reported while the work ran. A check leaves its finding in
   * the collection of the configuration instead of writing a line.
   */
  private List<String> reportedWhile(
      final MigrationAdapterProperties properties,
      final Runnable work) {

    work.run();
    return io.vanillabp.migration.test.startup.WhatWasFound.entries(properties.startupFindings());

  }


  @Test
  @DisplayName("Every element of a multi-instance activity runs its own handler")
  public void multiInstanceElementsAreToldApart() {

    final var testee = registry(processService(properties(null, null, null), deliveryLog));
    storeAggregate("4718");

    // one task of one workflow, three activations. The adapter reports a delivery id per
    // activation (the job key on Camunda 8, the task id on the Process-Engine-API). That is
    // the whole reason why no multi-instance index has to enter the key. Sequential and
    // parallel multi-instance look exactly the same from here
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-1"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-2"));
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-3"));

    assertEquals(3, persistence.aggregates.get("4718").invocations, "every element ran its handler");
    assertEquals(3, deliveryLog.records.size(), "every element got its own record");

    // and a redelivery of ONE element is still answered from the record of that element
    final var redelivered = testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4718", "job-2"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, redelivered.kind());
    assertEquals(3, persistence.aggregates.get("4718").invocations, "the redelivery ran no handler");

  }

  @Test
  @DisplayName("A called process is told apart even where the BPMS repeats a delivery id")
  public void aCalledProcessBringsItsOwnBpmnProcessId() {

    final var properties = properties(null, null, null);
    final var testee = registry(processService(properties, deliveryLog));
    // the called process of a multi-instance call activity is a secondary workflow of the
    // same aggregate. So only the BPMN process id and the delivery id tell its tasks apart
    // from those of the caller. The argument counts no levels. That is why this one test
    // covers any nesting depth
    register(testee, "CalledProcess", processService(properties, deliveryLog, "CalledProcess"));
    storeAggregate("4719");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery(TASK, "4719", "job-1"));
    testee.invokeWorkflowTask(MODULE, "CalledProcess", delivery(TASK, "4719", "job-1"));

    assertEquals(2, persistence.aggregates.get("4719").invocations, "both handlers ran");
    assertEquals(2, deliveryLog.records.size(), "the two deliveries are two records");

  }

}
