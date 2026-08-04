package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Edge cases of the <code>&#64;WorkflowTask</code> scanner and parameter binding:
 * guiding registration-time failures for unsupported parameter declarations,
 * task-parameter type conversion, version-range formats and checked-exception
 * wrapping - paths practically never hit at runtime but part of the guiding-UX
 * contract.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskScannerEdgeCasesTest {

  public static class Aggregate {

    String id;

    Object value;

  }

  private WorkflowTaskRegistry registry;

  private final Map<String, Aggregate> aggregates = new HashMap<>();

  @BeforeEach
  public void setUpRegistry() {

    registry = new WorkflowTaskRegistry(new WorkflowTaskRegistryTest.RecordingTransactionRunner());
    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    aggregates.put("4711", aggregate);

  }

  private MigrationProcessService<Aggregate> createProcessService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
    final var persistence = new AggregatePersistenceAware<Aggregate>() {

      @Override
      public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
      }

      @Override
      public Aggregate save(
          final Aggregate aggregate) {
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

    };
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

      @Override
      public void completeTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Aggregate workflowAggregate,
          final String taskId) {
      }

      @Override
      public void completeTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId) {
      }

      @Override
      public void cancelTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Aggregate workflowAggregate,
          final String taskId,
          final String bpmnErrorCode) {
      }

      @Override
      public void cancelTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId,
          final String bpmnErrorCode) {
      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final Object workflowAggregateId,
          final String taskId) {
        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public void completeUserTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Aggregate workflowAggregate,
          final String taskId) {
      }

      @Override
      public void completeUserTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId) {
      }

      @Override
      public void cancelUserTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Aggregate workflowAggregate,
          final String taskId,
          final String bpmnErrorCode) {
      }

      @Override
      public void cancelUserTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId,
          final String bpmnErrorCode) {
      }

    };
    return new MigrationProcessService<>(
        "test-module", "TestProcess", Aggregate.class, properties, persistence, List.of(adapterProcessService), null);

  }

  private void register(
      final Class<?> serviceClass,
      final Object bean) {

    registry.registerWorkflowService(
        "test-module",
        "TestProcess",
        serviceClass,
        () -> bean,
        type -> null,
        createProcessService());

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final Object parameterValue) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

      @Override
      public Object getTaskParameter(
          final String name) {
        return parameterValue;
      }

    };

  }

  @Nested
  @DisplayName("Registration-time failures")
  class RegistrationFailures {

    @Test
    @DisplayName("@TaskId parameter not of type String fails guiding")
    public void taskIdWrongType() {

      class Broken {

        @WorkflowTask
        public void task(
            final Aggregate aggregate,
            @TaskId final Long taskId) {
        }

      }
      final var e = assertThrows(IllegalStateException.class, () -> register(Broken.class, new Broken()));
      assertTrue(e.getMessage().contains("@TaskId"));
      assertTrue(e.getMessage().contains("String"));

    }

    @Test
    @DisplayName("@TaskEvent parameter not of type TaskEvent.Event fails guiding")
    public void taskEventWrongType() {

      class Broken {

        @WorkflowTask
        public void task(
            final Aggregate aggregate,
            @TaskEvent final String event) {
        }

      }
      final var e = assertThrows(IllegalStateException.class, () -> register(Broken.class, new Broken()));
      assertTrue(e.getMessage().contains("@TaskEvent"));

    }

    @Test
    @DisplayName("@MultiInstanceIndex parameter not of type int fails guiding")
    public void multiInstanceIndexWrongType() {

      class Broken {

        @WorkflowTask
        public void task(
            final Aggregate aggregate,
            @MultiInstanceIndex("items") final String index) {
        }

      }
      final var e = assertThrows(IllegalStateException.class, () -> register(Broken.class, new Broken()));
      assertTrue(e.getMessage().contains("@MultiInstanceIndex"));
      assertTrue(e.getMessage().contains("int"));

    }

    @Test
    @DisplayName("@MultiInstanceElement without value and without resolver fails guiding")
    public void multiInstanceElementNeither() {

      class Broken {

        @WorkflowTask
        public void task(
            final Aggregate aggregate,
            @MultiInstanceElement final Object element) {
        }

      }
      final var e = assertThrows(IllegalStateException.class, () -> register(Broken.class, new Broken()));
      assertTrue(e.getMessage().contains("EITHER"));

    }

  }

  @Nested
  @DisplayName("Task-parameter conversion")
  class ParameterConversion {

    class ConversionService {

      @WorkflowTask
      public void intParam(
          final Aggregate aggregate,
          @TaskParam("value") final int value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void longParam(
          final Aggregate aggregate,
          @TaskParam("value") final Long value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void doubleParam(
          final Aggregate aggregate,
          @TaskParam("value") final Double value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void booleanParam(
          final Aggregate aggregate,
          @TaskParam("value") final boolean value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void bigDecimalParam(
          final Aggregate aggregate,
          @TaskParam("value") final BigDecimal value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void bigIntegerParam(
          final Aggregate aggregate,
          @TaskParam("value") final BigInteger value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void stringParam(
          final Aggregate aggregate,
          @TaskParam("value") final String value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void shortParam(
          final Aggregate aggregate,
          @TaskParam("value") final Short value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void byteParam(
          final Aggregate aggregate,
          @TaskParam("value") final Byte value) {
        aggregates.get("4711").value = value;
      }

      @WorkflowTask
      public void floatParam(
          final Aggregate aggregate,
          @TaskParam("value") final Float value) {
        aggregates.get("4711").value = value;
      }

    }

    @BeforeEach
    public void registerService() {

      register(ConversionService.class, new ConversionService());

    }

    private Object invoke(
        final String task,
        final Object value) {

      registry.invokeWorkflowTask("test-module", "TestProcess", context(task, value));
      return aggregates.get("4711").value;

    }

    @Test
    @DisplayName("Strings convert to numbers, booleans and big types")
    public void stringConversions() {

      assertEquals(42, invoke("intParam", "42"));
      assertEquals(42L, invoke("longParam", "42"));
      assertEquals(1.5d, invoke("doubleParam", "1.5"));
      assertEquals(true, invoke("booleanParam", "true"));
      assertEquals(new BigDecimal("1.23"), invoke("bigDecimalParam", "1.23"));
      assertEquals(new BigInteger("123"), invoke("bigIntegerParam", "123"));
      assertEquals((short) 4, invoke("shortParam", "4"));
      assertEquals((byte) 2, invoke("byteParam", "2"));
      assertEquals(0.5f, invoke("floatParam", "0.5"));

    }

    @Test
    @DisplayName("Numbers widen/narrow between number types and stringify")
    public void numberConversions() {

      assertEquals(42, invoke("intParam", 42L));
      assertEquals(42L, invoke("longParam", 42));
      assertEquals(2.0d, invoke("doubleParam", 2));
      assertEquals(new BigDecimal("7"), invoke("bigDecimalParam", 7));
      assertEquals(new BigInteger("7"), invoke("bigIntegerParam", 7));
      assertEquals("7", invoke("stringParam", 7));
      assertEquals((short) 7, invoke("shortParam", 7));
      assertEquals((byte) 7, invoke("byteParam", 7));
      assertEquals(7.0f, invoke("floatParam", 7));

    }

    @Test
    @DisplayName("Assignable values pass through unchanged")
    public void passThrough() {

      assertEquals("as-is", invoke("stringParam", "as-is"));
      assertEquals(11, invoke("intParam", 11));

    }

    @Test
    @DisplayName("null into a primitive parameter fails guiding")
    public void nullIntoPrimitive() {

      final var e = assertThrows(
          IllegalStateException.class,
          () -> invoke("intParam", null));
      assertTrue(e.getMessage().contains("primitive"));

    }

    @Test
    @DisplayName("null into a wrapper parameter binds null")
    public void nullIntoWrapper() {

      aggregates.get("4711").value = "sentinel";
      registry.invokeWorkflowTask("test-module", "TestProcess", context("longParam", null));
      assertEquals(null, aggregates.get("4711").value);

    }

    @Test
    @DisplayName("An inconvertible value fails guiding")
    public void inconvertible() {

      final var e = assertThrows(
          IllegalStateException.class,
          () -> invoke("intParam", new Object()));
      assertTrue(e.getMessage().contains("cannot be converted"));

    }

  }

  @Nested
  @DisplayName("Version formats")
  class VersionFormats {

    class VersionService {

      @WorkflowTask(taskDefinition = "task", version = "2")
      public void exactVersion(
          final Aggregate aggregate) {
        aggregates.get("4711").value = "exact";
      }

      @WorkflowTask(taskDefinition = "task", version = "<2")
      public void lessThan(
          final Aggregate aggregate) {
        aggregates.get("4711").value = "less";
      }

      @WorkflowTask(taskDefinition = "task", version = ">2")
      public void greaterThan(
          final Aggregate aggregate) {
        aggregates.get("4711").value = "greater";
      }

    }

    private TaskInvocationContext versioned(
        final String version) {

      return new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "task";
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
    @DisplayName("Exact, less-than and greater-than specifications match correctly")
    public void versionFormats() {

      register(VersionService.class, new VersionService());

      registry.invokeWorkflowTask("test-module", "TestProcess", versioned("2"));
      assertEquals("exact", aggregates.get("4711").value);
      registry.invokeWorkflowTask("test-module", "TestProcess", versioned("1"));
      assertEquals("less", aggregates.get("4711").value);
      registry.invokeWorkflowTask("test-module", "TestProcess", versioned("3"));
      assertEquals("greater", aggregates.get("4711").value);

      // a non-numeric process version matches no numeric specification
      final var e = assertThrows(
          IllegalStateException.class,
          () -> registry.invokeWorkflowTask("test-module", "TestProcess", versioned("beta")));
      assertTrue(e.getMessage().contains("beta"));

    }

  }

  @Test
  @DisplayName("A checked exception of a handler is wrapped with the method named")
  public void checkedExceptionIsWrapped() {

    class CheckedService {

      @WorkflowTask
      public void throwsChecked(
          final Aggregate aggregate) throws Exception {
        throw new Exception("checked");
      }

    }
    register(CheckedService.class, new CheckedService());

    final var e = assertThrows(
        IllegalStateException.class,
        () -> registry.invokeWorkflowTask("test-module", "TestProcess", context("throwsChecked", null)));
    assertTrue(e.getMessage().contains("threw a checked exception"));
    assertEquals("checked", e.getCause().getMessage());

  }

  @Test
  @DisplayName("A repeated @WorkflowTask annotation wires one method to several tasks")
  public void repeatableAnnotation() {

    class RepeatedService {

      @WorkflowTask(taskDefinition = "first")
      @WorkflowTask(taskDefinition = "second")
      public void serveBoth(
          final Aggregate aggregate) {
        aggregates.get("4711").value = "served";
      }

    }
    register(RepeatedService.class, new RepeatedService());

    registry.invokeWorkflowTask("test-module", "TestProcess", context("first", null));
    assertEquals("served", aggregates.get("4711").value);
    aggregates.get("4711").value = null;
    registry.invokeWorkflowTask("test-module", "TestProcess", context("second", null));
    assertEquals("served", aggregates.get("4711").value);

  }

}
