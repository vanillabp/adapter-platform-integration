package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * What a wake-up does with the OTHER tasks VanillaBP still believes are open in the same
 * workflow: it asks the adapter's probe about them and reports the ones which are gone as
 * canceled.
 * <p>
 * The three answers of the probe are why this is a test of its own. A cancellation follows
 * only on {@link TaskExistence#GONE}, and a probe which cannot say is not a probe which
 * said no - reading a failed question as "gone" would cancel the open work of a workflow
 * whenever the engine hiccups.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OtherOpenTasksOfAWakeUpTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "SignatureProcess";

  private static final String SYNCHRONOUS_PROCESS = "FireAndForgetProcess";

  private static final String ADAPTER = "test-adapter";

  private static final String WORKFLOW = "2251799813685249";

  public static class Aggregate {

    @Getter
    String id;

    String canceled;

    void wasCanceled(
        final String taskId) {

      canceled = canceled == null
          ? taskId
          : canceled
              + ","
              + taskId;

    }

  }

  /**
   * A method which keeps its task open and wants to hear about the cancellation, which is
   * the only shape this whole mechanism is about.
   */
  @WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class SignatureService {

    @WorkflowTask
    public void awaitSignature(
        final Aggregate aggregate,
        @TaskId final String taskId,
        @TaskEvent({
            TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
        }) final TaskEvent.Event event) {

      if (event == TaskEvent.Event.CANCELED) {
        aggregate.wasCanceled(taskId);
      }

    }

    /**
     * A second open task of the same BPMN process, so a test can show a probe answering
     * for one task definition and refusing for the other.
     */
    @WorkflowTask
    public void countersign(
        final Aggregate aggregate,
        @TaskId final String taskId,
        @TaskEvent({
            TaskEvent.Event.CREATED, TaskEvent.Event.CANCELED
        }) final TaskEvent.Event event) {

      if (event == TaskEvent.Event.CANCELED) {
        aggregate.wasCanceled(taskId);
      }

    }

  }

  /**
   * A BPMN process whose method is done when it returns, so nothing of it can ever be
   * open - which is what most applications look like.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SYNCHRONOUS_PROCESS))
  public static class FireAndForgetService {

    @WorkflowTask
    public void doIt(
        final Aggregate aggregate) {

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
   * A store which remembers what was asked of it, so a test can show that a BPMN process
   * without an asynchronous task does not read it at all.
   */
  static class RecordingDeliveryLog implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new java.util.LinkedHashMap<>();

    int readsOfTheWorkflow;

    @Override
    public java.util.Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {
      return java.util.Optional.ofNullable(records.get(deliveryKey));
    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {
      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;
    }

    @Override
    public List<TaskDelivery> openTasksOfWorkflow(
        final String workflowModuleId,
        final String workflowId) {

      ++readsOfTheWorkflow;
      return records
          .values()
          .stream()
          .filter(record -> workflowModuleId.equals(record.workflowModuleId()))
          .filter(record -> workflowId.equals(record.workflowId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .filter(record -> record.taskClosedAt() == null)
          .sorted(java.util.Comparator.comparing(TaskDelivery::recordedAt))
          .toList();

    }

    @Override
    public int markTaskClosed(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String taskId) {

      var marked = 0;
      for (final var entry : records.entrySet()) {
        final var record = entry.getValue();
        if (!taskId.equals(record.taskId()) || (record.taskClosedAt() != null)) {
          continue;
        }
        entry
            .setValue(
                new TaskDelivery(record.deliveryKey(), record.adapterId(), record.workflowModuleId(), record
                    .bpmnProcessId(), record.workflowAggregateId(), record.workflowId(), record
                        .taskDefinition(), record.bpmnElementId(), record.taskId(), record
                            .outcome(), record.bpmnErrorCode(), record.bpmnErrorName(), record
                                .recordedAt(), Instant.now()));
        ++marked;
      }
      return marked;

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final RecordingDeliveryLog deliveryLog = new RecordingDeliveryLog();

  /**
   * What the probe answered per task, and which tasks it was asked about at all.
   */
  private final List<String> probedTasks = new java.util.ArrayList<>();

  private MigrationAdapterProperties properties(
      final Integer maxOpenTasksChecked,
      final Boolean checkOpenTasksOnDelivery) {

    final var delivery = new DeliveryProperties();
    delivery.setMaxOpenTasksChecked(maxOpenTasksChecked);
    delivery.setCheckOpenTasksOnDelivery(checkOpenTasksOnDelivery);
    final var module = new WorkflowModuleAdapterProperties();
    module.setDelivery(delivery);
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .workflowModules(Map.of(MODULE, module))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Aggregate> processService(
      final String bpmnProcessId,
      final MigrationAdapterProperties properties) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient()
        .when(adapter.getAdapterId())
        .thenReturn(ADAPTER);

    return MigrationProcessService
        .forBpmnProcess(MODULE, bpmnProcessId, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .taskDeliveryLogResolver(
            new io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver() {

              @Override
              public TaskDeliveryLog resolveFor(
                  final Class<?> workflowAggregateClass) {
                return deliveryLog;
              }

              @Override
              public String remediesDescription() {
                return "";
              }

            })
        .build();

  }

  private WorkflowTaskRegistry registry(
      final MigrationAdapterProperties properties) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(
            MODULE, PROCESS, SignatureService.class, SignatureService::new, type -> null, processService(
                PROCESS, properties));
    registry
        .registerWorkflowService(
            MODULE,
            SYNCHRONOUS_PROCESS,
            FireAndForgetService.class,
            FireAndForgetService::new,
            type -> null,
            processService(SYNCHRONOUS_PROCESS, properties));
    return registry;

  }

  private Aggregate storeAggregate() {

    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.aggregates.put("4711", aggregate);
    return aggregate;

  }

  /**
   * One delivery, as an adapter of a remote BPMS builds it.
   */
  private TaskInvocationContext delivery(
      final String bpmnProcessId,
      final String taskDefinition,
      final String taskId) {

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
        return "4711";
      }

      @Override
      public String getWorkflowId() {
        return WORKFLOW;
      }

      @Override
      public String getTaskId() {
        return taskId;
      }

      @Override
      public String getDeliveryId() {
        return "job-of-"
            + taskId;
      }

    };

  }

  /**
   * A probe answering per task, and writing down what it was asked about.
   */
  private OpenTaskProbe probeAnswering(
      final Map<String, TaskExistence> answers) {

    return (
        workflowId,
        taskId) -> {
      probedTasks.add(taskId);
      return answers.getOrDefault(taskId, TaskExistence.STILL_THERE);
    };

  }

  private int openRecords() {

    return (int) deliveryLog.records
        .values()
        .stream()
        .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
        .filter(record -> record.taskClosedAt() == null)
        .count();

  }

  @Test
  @DisplayName("A task the probe calls gone is reported once and its record is closed")
  public void aTaskWhichIsGoneIsReportedOnce() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    final var aggregate = storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));
    assertEquals(2, openRecords());

    // task-2 woke us up, so it is not probed, and task-1 is gone
    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-2"),
            probeAnswering(Map.of("task-1", TaskExistence.GONE)));

    assertEquals(List.of("task-1"), probedTasks, "the task of the wake-up itself is never probed");
    assertEquals("task-1", aggregate.canceled, "the method asked for the event, so it was called with it");
    assertEquals(1, openRecords(), "the record of the gone task is closed, the one of the wake-up is not");

    // a second wake-up finds nothing left to probe
    probedTasks.clear();
    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-2"),
            probeAnswering(Map.of("task-1", TaskExistence.GONE)));
    assertTrue(probedTasks.isEmpty(), "a closed record is not a candidate any more");
    assertEquals("task-1", aggregate.canceled, "so the cancellation is reported exactly once");

  }

  @Test
  @DisplayName("A task which is still there and one the probe cannot answer for are both left alone")
  public void whatIsNotGoneIsUntouched() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    final var aggregate = storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-3"));

    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-3"),
            probeAnswering(
                Map.of("task-1", TaskExistence.STILL_THERE, "task-2", TaskExistence.CANNOT_SAY)));

    assertEquals(List.of("task-1", "task-2"), probedTasks);
    assertEquals(null, aggregate.canceled, "neither answer is a cancellation");
    assertEquals(3, openRecords(), "and no record was closed");

  }

  @Test
  @DisplayName("More open tasks than the maximum: the oldest are probed, the rest wait for the next wake-up")
  public void theOldestAreProbedFirst() {

    final var properties = properties(2, null);
    final var registry = registry(properties);
    storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-3"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-4"));

    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-4"),
            probeAnswering(Map.of()));

    assertEquals(
        List.of("task-1", "task-2"),
        probedTasks,
        "two per wake-up, oldest first - the third one is reached at the next wake-up");

  }

  @Test
  @DisplayName("A BPMN process without an asynchronous task never reads the delivery log")
  public void aProcessWithoutAnOpenTaskPaysNothing() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    storeAggregate();

    registry.invokeWorkflowTask(MODULE, SYNCHRONOUS_PROCESS, delivery(SYNCHRONOUS_PROCESS, "doIt", "task-9"));
    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            SYNCHRONOUS_PROCESS,
            delivery(SYNCHRONOUS_PROCESS, "doIt", "task-9"),
            probeAnswering(Map.of()));

    assertEquals(0, deliveryLog.readsOfTheWorkflow, "nothing of this process can ever be open");
    assertTrue(probedTasks.isEmpty());

  }

  @Test
  @DisplayName("An application which switched the check off keeps behaving as it did")
  public void theCheckCanBeSwitchedOff() {

    final var properties = properties(null, Boolean.FALSE);
    final var registry = registry(properties);
    storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));

    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-2"),
            probeAnswering(Map.of("task-1", TaskExistence.GONE)));

    assertEquals(0, deliveryLog.readsOfTheWorkflow, "the log is not even read");
    assertEquals(2, openRecords());

  }

  @Test
  @DisplayName("A probe written before the task definition travelled is asked through the default and still decides")
  public void aProbeKnowingOnlyTheTwoIdsKeepsDeciding() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    final var aggregate = storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));

    // a probe implementing the two-argument method alone, which is every probe written
    // before the definition travelled
    final var asked = new java.util.ArrayList<String>();
    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-2"),
            (
                workflowId,
                taskId) -> {
              asked
                  .add(workflowId
                      + "/"
                      + taskId);
              return TaskExistence.GONE;
            });

    assertEquals(List.of(WORKFLOW
        + "/task-1"), asked, "it is still asked, with the two ids it always got");
    assertEquals("task-1", aggregate.canceled, "and its answer still cancels");

  }

  @Test
  @DisplayName("A probe is told the task definition of every record it is asked about")
  public void aProbeLearnsWhichKindOfTaskItIsAskedAbout() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    final var aggregate = storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "countersign", "task-2"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-3"));

    // what an adapter of a BPMS which answers for some kinds of task and not for others
    // does: it refuses per record instead of per BPMN process
    final var askedWithTheDefinition = new java.util.LinkedHashMap<String, String>();
    final var askedWithoutIt = new java.util.ArrayList<String>();
    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-3"),
            new OpenTaskProbe() {

              @Override
              public TaskExistence stillExists(
                  final String workflowId,
                  final String taskId) {

                askedWithoutIt.add(taskId);
                return TaskExistence.CANNOT_SAY;

              }

              @Override
              public TaskExistence stillExists(
                  final String workflowId,
                  final String taskId,
                  final String taskDefinition) {

                askedWithTheDefinition.put(taskId, taskDefinition);
                return "countersign".equals(taskDefinition)
                    ? TaskExistence.CANNOT_SAY
                    : TaskExistence.GONE;

              }

            });

    assertTrue(askedWithoutIt.isEmpty(), "the core asks the question which names the task definition");
    assertEquals(
        Map.of("task-1", "awaitSignature", "task-2", "countersign"),
        askedWithTheDefinition,
        "every record carries the definition it was delivered under");
    // both facts in one assertion, so nobody can pass this by weakening the model: the
    // task the probe could answer for is canceled and the one it refused is left alone
    assertEquals(
        "task-1",
        aggregate.canceled,
        "the refusal is per record, so the other task of the same workflow still decides");
    assertEquals(2, openRecords(), "the refused task and the one of the wake-up stay open");

  }

  @Test
  @DisplayName("A probe which throws is read as 'cannot say', and the delivery which led here is not lost")
  public void aProbeWhichThrowsCancelsNothing() {

    final var properties = properties(null, null);
    final var registry = registry(properties);
    final var aggregate = storeAggregate();

    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-1"));
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery(PROCESS, "awaitSignature", "task-2"));

    registry
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery(PROCESS, "awaitSignature", "task-2"),
            (
                workflowId,
                taskId) -> {
              throw new IllegalStateException("the BPMS is unreachable");
            });

    assertEquals(null, aggregate.canceled);
    assertEquals(2, openRecords(), "an unreachable BPMS must not cancel the open work of a workflow");

  }

}
