package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Unit tests of the core-owned <code>&#64;WorkflowTask</code> handler model: method
 * scanning and parameter binding, the three invocation outcomes (completed / BPMN
 * error / failure), version-range matching and the two-directional wiring
 * validation with guiding messages.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskRegistryTest {

  public static class Aggregate {

    String id;

    String processedBy;

    Object element;

    int index;

    int total;

    String parameterValue;

    String taskId;

    TaskEvent.Event event;

  }

  /**
   * Records requireNew/inCurrent usage and mimics the transactional contract: a
   * RuntimeException thrown by the work propagates (the "rollback"), a normal
   * return is the "commit".
   */
  static class RecordingTransactionRunner implements TransactionRunner {

    boolean requireNewUsed = false;

    boolean inCurrentUsed = false;

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {

      requireNewUsed = true;
      return work.get();

    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {

      inCurrentUsed = true;
      return work.get();

    }

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<String, Aggregate> aggregates = new HashMap<>();

    boolean saved = false;

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      saved = true;
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class SampleService {

    @WorkflowTask
    public void doSomething(
        final Aggregate aggregate) {

      aggregate.processedBy = "doSomething";

    }

    @WorkflowTask(taskDefinition = "explicitDefinition")
    public void byDefinition(
        final Aggregate aggregate) {

      aggregate.processedBy = "byDefinition";

    }

    @WorkflowTask(id = "Activity_4711")
    public void byActivityId(
        final Aggregate aggregate) {

      aggregate.processedBy = "byActivityId";

    }

    @WorkflowTask
    public void fails(
        final Aggregate aggregate) {

      aggregate.processedBy = "fails";
      throw new IllegalArgumentException("boom");

    }

    @WorkflowTask
    public void bpmnError(
        final Aggregate aggregate) {

      aggregate.processedBy = "bpmnError";
      throw new TaskException("SomethingWrong", "ERR-42");

    }

    @WorkflowTask
    public void asyncTask(
        final Aggregate aggregate,
        @TaskId final String taskId) {

      aggregate.processedBy = "asyncTask";
      aggregate.taskId = taskId;

    }

    @WorkflowTask
    public void withBindings(
        final Aggregate aggregate,
        @TaskParam("status") final String status,
        @TaskEvent final TaskEvent.Event event,
        @MultiInstanceIndex("items") final int index,
        @MultiInstanceTotal("items") final int total,
        @MultiInstanceElement("items") final Object element) {

      aggregate.processedBy = "withBindings";
      aggregate.parameterValue = status;
      aggregate.event = event;
      aggregate.index = index;
      aggregate.total = total;
      aggregate.element = element;

    }

    @WorkflowTask
    public void withResolver(
        final Aggregate aggregate,
        @MultiInstanceElement(resolverBean = ItemResolver.class) final Object element) {

      aggregate.processedBy = "withResolver";
      aggregate.element = element;

    }

  }

  public interface ItemResolver extends MultiInstanceElementResolver<Aggregate, Object> {
  }

  static class VersionedService {

    @WorkflowTask(taskDefinition = "versioned", version = "1-2")
    public void oldVersions(
        final Aggregate aggregate) {

      aggregate.processedBy = "oldVersions";

    }

    @WorkflowTask(taskDefinition = "versioned", version = ">2")
    public void newVersions(
        final Aggregate aggregate) {

      aggregate.processedBy = "newVersions";

    }

  }

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private RecordingTransactionRunner transactionRunner;

  private InMemoryPersistence persistence;

  private WorkflowTaskRegistry registry;

  private final SampleService serviceBean = new SampleService();

  private final Map<Class<?>, Object> beans = new HashMap<>();

  @BeforeEach
  public void setUpRegistry() {

    transactionRunner = new RecordingTransactionRunner();
    persistence = new InMemoryPersistence();
    registry = new WorkflowTaskRegistry(transactionRunner);
    registry.registerWorkflowService(
        MODULE,
        PROCESS,
        SampleService.class,
        () -> serviceBean,
        beans::get,
        createProcessService());

    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.aggregates.put("4711", aggregate);

  }

  private MigrationProcessService<Aggregate> createProcessService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var adapterProcessService = new MigratableProcessService<Aggregate>() {

      @Override
      public String getAdapterId() {
        return "test-adapter";
      }

      @Override
      public WorkflowAwareness awarenessOfTask(
          final Object workflowAggregateId,
          final String taskId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public WorkflowAwareness awarenessOfWorkflow(
          final Object workflowAggregateId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public boolean needsTwoPhaseCommitForStartingWorkflows() {
        return false;
      }

      @Override
      public void startWorkflowPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Aggregate workflowAggregate) {
      }

      @Override
      public void startWorkflowPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId) {
      }

    };
    return new MigrationProcessService<>(
        MODULE, PROCESS, Aggregate.class, properties, persistence, List.of(adapterProcessService), null);

  }

  private TaskInvocationContext context(
      final String taskDefinition) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

    };

  }

  @Nested
  @DisplayName("Invocation outcomes")
  class InvocationOutcomes {

    @Test
    @DisplayName("Normal return completes the task and saves the aggregate")
    public void normalReturnCompletes() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context("doSomething"));

      assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
      assertEquals("doSomething", persistence.aggregates.get("4711").processedBy);
      assertTrue(persistence.saved);
      assertTrue(transactionRunner.requireNewUsed);
      assertFalse(transactionRunner.inCurrentUsed);

    }

    @Test
    @DisplayName("TaskException yields a BPMN-error outcome and STILL saves the aggregate")
    public void taskExceptionYieldsBpmnErrorAndCommits() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context("bpmnError"));

      assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, outcome.kind());
      assertEquals("ERR-42", outcome.errorCode());
      assertEquals("SomethingWrong", outcome.errorName());
      // the V1 contract: aggregate changes commit although the handler threw
      assertEquals("bpmnError", persistence.aggregates.get("4711").processedBy);
      assertTrue(persistence.saved);

    }

    @Test
    @DisplayName("Any other exception propagates and the aggregate is NOT saved")
    public void otherExceptionPropagatesWithoutSaving() {

      final var exception = assertThrows(
          IllegalArgumentException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("fails")));

      assertEquals("boom", exception.getMessage());
      assertFalse(persistence.saved);

    }

    @Test
    @DisplayName("A @TaskId method yields COMPLETION_PENDING and receives the task's ID")
    public void asyncTaskYieldsCompletionPending() {

      final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "asyncTask";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public String getTaskId() {
          return "task-0815";
        }

      });

      assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, outcome.kind());
      assertEquals("task-0815", persistence.aggregates.get("4711").taskId);

    }

    @Test
    @DisplayName("runInCurrentTransaction() routes through TransactionRunner.inCurrent")
    public void currentTransactionRequested() {

      registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "doSomething";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public boolean runInCurrentTransaction() {
          return true;
        }

      });

      assertTrue(transactionRunner.inCurrentUsed);
      assertFalse(transactionRunner.requireNewUsed);

    }

    @Test
    @DisplayName("A missing aggregate fails with a guiding message")
    public void missingAggregateFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

            @Override
            public String getTaskDefinition() {
              return "doSomething";
            }

            @Override
            public String getWorkflowAggregateId() {
              return "no-such-id";
            }

          }));

      assertTrue(exception.getMessage().contains("no-such-id"));
      assertTrue(exception.getMessage().contains(Aggregate.class.getName()));

    }

    @Test
    @DisplayName("An unknown task definition fails naming the registered methods")
    public void unknownTaskDefinitionFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("noSuchTask")));

      assertTrue(exception.getMessage().contains("noSuchTask"));
      assertTrue(exception.getMessage().contains("doSomething"));

    }

    @Test
    @DisplayName("An unknown BPMN process fails naming the known processes")
    public void unknownProcessFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, "NoSuchProcess", context("doSomething")));

      assertTrue(exception.getMessage().contains("NoSuchProcess"));
      assertTrue(exception.getMessage().contains(PROCESS));

    }

  }

  @Nested
  @DisplayName("Parameter binding")
  class ParameterBinding {

    @Test
    @DisplayName("@TaskParam, @TaskEvent and @MultiInstance* parameters are bound from the context")
    public void allBindingsResolved() {

      final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
      multiInstances.put("items", new MultiInstanceValue("item-2", 2, 5));

      registry.invokeWorkflowTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "withBindings";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public Object getTaskParameter(
            final String name) {
          return "status".equals(name)
              ? "APPROVED"
              : null;
        }

        @Override
        public Map<String, MultiInstanceValue> getMultiInstances() {
          return multiInstances;
        }

      });

      final var aggregate = persistence.aggregates.get("4711");
      assertEquals("APPROVED", aggregate.parameterValue);
      assertEquals(TaskEvent.Event.CREATED, aggregate.event);
      assertEquals(2, aggregate.index);
      assertEquals(5, aggregate.total);
      assertEquals("item-2", aggregate.element);

    }

    @Test
    @DisplayName("@MultiInstanceElement(resolverBean = ...) resolves via the bean resolver")
    public void resolverBeanResolved() {

      final var resolved = new Object();
      beans.put(ItemResolver.class, new ItemResolver() {

        @Override
        public java.util.Collection<String> getNames() {
          return List.of("items");
        }

        @Override
        public Object resolve(
            final Aggregate workflowAggregate,
            final Map<String, MultiInstanceElementResolver.MultiInstance<Object>> multiInstances) {
          return resolved;
        }

      });

      registry.invokeWorkflowTask(MODULE, PROCESS, context("withResolver"));

      assertSame(resolved, persistence.aggregates.get("4711").element);

    }

    @Test
    @DisplayName("A missing multi-instance context fails with a guiding message")
    public void missingMultiInstanceFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask(MODULE, PROCESS, context("withBindings")));

      assertTrue(exception.getMessage().contains("items"));
      assertTrue(exception.getMessage().contains("withBindings"));

    }

    @Test
    @DisplayName("An unbindable parameter fails at registration with a guiding message")
    public void unbindableParameterFailsAtRegistration() {

      class BrokenService {

        @WorkflowTask
        public void broken(
            final Aggregate aggregate,
            final String unannotated) {
        }

      }

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.registerWorkflowService(
              MODULE,
              "OtherProcess",
              BrokenService.class,
              BrokenService::new,
              beans::get,
              createProcessService()));

      assertTrue(exception.getMessage().contains("broken"));
      assertTrue(exception.getMessage().contains("@TaskParam"));

    }

    @Test
    @DisplayName("Two methods wired to the same task definition fail at registration")
    public void duplicateWiringFailsAtRegistration() {

      class DuplicateService {

        @WorkflowTask(taskDefinition = "doSomething")
        public void collides(
            final Aggregate aggregate) {
        }

      }

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.registerWorkflowService(
              MODULE,
              PROCESS,
              DuplicateService.class,
              DuplicateService::new,
              beans::get,
              createProcessService()));

      assertTrue(exception.getMessage().contains("doSomething"));
      assertTrue(exception.getMessage().contains("version"));

    }

  }

  @Nested
  @DisplayName("Version ranges")
  class VersionRanges {

    @BeforeEach
    public void registerVersionedService() {

      registry.registerWorkflowService(
          MODULE,
          "VersionedProcess",
          VersionedService.class,
          VersionedService::new,
          beans::get,
          createProcessService());

    }

    private TaskInvocationContext versionedContext(
        final String version) {

      return new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "versioned";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4711";
        }

        @Override
        public String getProcessVersion() {
          return version;
        }

      };

    }

    @Test
    @DisplayName("The handler matching the process version wins")
    public void versionRangesAreHonored() {

      registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext("2"));
      assertEquals("oldVersions", persistence.aggregates.get("4711").processedBy);

      registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext("3"));
      assertEquals("newVersions", persistence.aggregates.get("4711").processedBy);

    }

    @Test
    @DisplayName("A null process version matches any handler")
    public void nullVersionMatchesAll() {

      registry.invokeWorkflowTask(MODULE, "VersionedProcess", versionedContext(null));
      assertEquals("oldVersions", persistence.aggregates.get("4711").processedBy);

    }

  }

  @Nested
  @DisplayName("Wiring validation")
  class WiringValidation {

    @Test
    @DisplayName("A complete wiring passes silently")
    public void completeWiringPasses() {

      registry.validateTaskWiring(
          MODULE,
          PROCESS,
          List.of(
              new BpmnTaskSpec("Activity_1", "doSomething"),
              new BpmnTaskSpec("Activity_2", "explicitDefinition"),
              new BpmnTaskSpec("Activity_4711", "somethingElse"),
              new BpmnTaskSpec("Activity_3", "fails"),
              new BpmnTaskSpec("Activity_5", "bpmnError"),
              new BpmnTaskSpec("Activity_6", "asyncTask"),
              new BpmnTaskSpec("Activity_7", "withBindings"),
              new BpmnTaskSpec("Activity_8", "withResolver")));

    }

    @Test
    @DisplayName("A BPMN task without handler fails naming the task and the fix")
    public void unmatchedTaskFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateTaskWiring(
              MODULE,
              PROCESS,
              List.of(new BpmnTaskSpec("Activity_9", "notImplemented"))));

      final var message = exception.getMessage();
      assertTrue(message.contains("'Activity_9'"));
      assertTrue(message.contains("'notImplemented'"));
      assertTrue(message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"));
      assertTrue(message.contains(SampleService.class.getName()));
      // the second direction is reported in the SAME exception (all defects at once)
      assertTrue(message.contains("@WorkflowTask methods matching no task"));
      assertTrue(message.contains("doSomething"));

    }

    @Test
    @DisplayName("A handler without BPMN task fails naming the method")
    public void unmatchedHandlerFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateTaskWiring(
              MODULE,
              PROCESS,
              List.of(
                  new BpmnTaskSpec("Activity_1", "doSomething"),
                  new BpmnTaskSpec("Activity_2", "explicitDefinition"),
                  new BpmnTaskSpec("Activity_4711", "x"),
                  new BpmnTaskSpec("Activity_3", "fails"),
                  new BpmnTaskSpec("Activity_5", "bpmnError"),
                  new BpmnTaskSpec("Activity_6", "asyncTask"),
                  new BpmnTaskSpec("Activity_7", "withBindings"))));

      final var message = exception.getMessage();
      assertTrue(message.contains("withResolver"));
      assertTrue(message.contains("fix the annotation"));

    }

    @Test
    @DisplayName("A process without any @WorkflowService fails guiding to create one")
    public void noServiceRegisteredFails() {

      final var exception = assertThrows(
          IllegalStateException.class,
          () -> registry.validateTaskWiring(
              MODULE,
              "UnknownProcess",
              List.of(new BpmnTaskSpec("Activity_1", "someTask"))));

      assertTrue(exception.getMessage().contains("a @WorkflowService class responsible for this BPMN process"));

    }

  }

  @Test
  @DisplayName("TaskEvent.Event defaults to CREATED and @TaskId to null if the context does not supply them")
  public void contextDefaults() {

    final var context = context("asyncTask");
    assertNull(context.getTaskId());
    assertEquals(TaskEvent.Event.CREATED, context.getTaskEvent());

    final var outcome = registry.invokeWorkflowTask(MODULE, PROCESS, context);
    // a @TaskId method stays open even if the BPMS supplies no task ID
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, outcome.kind());
    assertNull(persistence.aggregates.get("4711").taskId);

  }

  @Test
  @DisplayName("An unsupported version specification fails at registration")
  public void unsupportedVersionSpecFails() {

    class BadVersionService {

      @WorkflowTask(version = "latest")
      public void badVersion(
          final Aggregate aggregate) {
      }

    }

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.registerWorkflowService(
            MODULE,
            "BadVersionProcess",
            BadVersionService.class,
            BadVersionService::new,
            beans::get,
            createProcessService()));

    assertTrue(exception.getMessage().contains("latest"));
    assertTrue(exception.getMessage().contains("Supported formats"));

  }

}
