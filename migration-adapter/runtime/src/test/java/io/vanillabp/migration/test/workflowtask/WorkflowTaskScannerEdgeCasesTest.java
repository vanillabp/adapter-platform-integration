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

    registry = new WorkflowTaskRegistry(
        new WorkflowTaskRegistryTest.RecordingTransactionRunner(), null, TransactionAnnotationSpecs
            .ofATypicalPlatform());
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
      public java.util.Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Aggregate>> phaseOperations() {
        return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();
      }

      @Override
      public WorkflowAwareness awarenessOfTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public WorkflowAwareness awarenessOfWorkflow(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final io.vanillabp.integration.spi.AggregatePersistenceAware<Aggregate> aggregatePersistence,
          final Object workflowAggregateId) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {
        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
      }

    };
    return MigrationProcessService
        .forBpmnProcess("test-module", "TestProcess", Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapterProcessService))
        .build();

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

  /**
   * The startup check for a handler starting its own transaction. The annotations are
   * matched by type name, so the
   * stand-ins in this module's test sources (see
   * {@link org.springframework.transaction.annotation.Transactional}) exercise exactly
   * what the real ones do; the real annotations are used by the acceptance tests of
   * both platform integrations.
   */
  @Nested
  @DisplayName("Transaction annotations of the application")
  class ApplicationTransactions {

    /**
     * Registers the workflow service and returns the warnings the scanner logged while
     * doing so (the module's logback-test.xml has no appender on purpose).
     */
    private List<String> registerCollectingWarnings(
        final Class<?> serviceClass,
        final Object bean) {

      final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
      logWatcher.start();
      final var scannerLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
          .getLogger("io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskScanner");
      scannerLog.addAppender(logWatcher);
      try {
        register(serviceClass, bean);
      } finally {
        scannerLog.detachAndStopAllAppenders();
      }
      return logWatcher.list
          .stream()
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .toList();

    }

    private IllegalStateException assertRejected(
        final Class<?> serviceClass,
        final Object bean) {

      final var e = assertThrows(IllegalStateException.class, () -> register(serviceClass, bean));
      assertTrue(
          e.getMessage().contains("covered by a transaction annotation of the application"),
          e.getMessage());
      // the way out that always exists, no matter which annotation was found
      assertTrue(
          e.getMessage().contains("remove the annotation from the workflow task method"),
          e.getMessage());
      return e;

    }

    @Test
    @DisplayName("Spring's @Transactional on the method fails the startup naming method and annotation")
    public void springOnMethod() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional
        public void task(
            final Aggregate aggregate) {
        }

      }
      final var e = assertRejected(Service.class, new Service());
      assertTrue(e.getMessage().contains("#task"), e.getMessage());
      assertTrue(
          e.getMessage().contains("org.springframework.transaction.annotation.Transactional"),
          e.getMessage());
      assertTrue(e.getMessage().contains("declared on the method"), e.getMessage());
      // the remedy of the OFFENDING annotation, not of every known one
      assertTrue(e.getMessage().contains("noRollbackFor = TaskException.class"), e.getMessage());
      assertTrue(!e.getMessage().contains("dontRollbackOn = TaskException.class"), e.getMessage());

    }

    @Test
    @DisplayName("Spring's @Transactional on the class fails the startup naming the class")
    public void springOnClass() {

      final var e = assertRejected(SpringOnClassService.class, new SpringOnClassService());
      assertTrue(e.getMessage().contains("declared on the class"), e.getMessage());
      assertTrue(e.getMessage().contains(SpringOnClassService.class.getName()), e.getMessage());

    }

    @Test
    @DisplayName("noRollbackFor covering TaskException is accepted (the version-1 pattern)")
    public void springNoRollbackForTaskException() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            noRollbackFor = io.vanillabp.spi.service.TaskException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(Service.class, new Service());

    }

    @Test
    @DisplayName("noRollbackFor naming a superclass of TaskException is accepted")
    public void springNoRollbackForSuperclass() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(noRollbackFor = RuntimeException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(Service.class, new Service());

    }

    @Test
    @DisplayName("noRollbackForClassName matching TaskException by name is accepted")
    public void springNoRollbackForClassName() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(noRollbackForClassName = "TaskException")
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(Service.class, new Service());

    }

    @Test
    @DisplayName("Propagations not joining VanillaBP's transaction are accepted")
    public void springNonJoiningPropagations() {

      class RequiresNew {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        public void task(
            final Aggregate aggregate) {
        }

      }
      class NestedPropagation {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NESTED)
        public void task(
            final Aggregate aggregate) {
        }

      }
      class Never {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NEVER)
        public void task(
            final Aggregate aggregate) {
        }

      }
      class NotSupported {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(RequiresNew.class, new RequiresNew());
      setUpRegistry();
      register(NestedPropagation.class, new NestedPropagation());
      setUpRegistry();
      register(Never.class, new Never());
      setUpRegistry();
      register(NotSupported.class, new NotSupported());

    }

    @Test
    @DisplayName("SUPPORTS and MANDATORY join VanillaBP's transaction and fail the startup")
    public void springJoiningPropagations() {

      class Supports {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.SUPPORTS)
        public void task(
            final Aggregate aggregate) {
        }

      }
      class Mandatory {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
        public void task(
            final Aggregate aggregate) {
        }

      }
      assertRejected(Supports.class, new Supports());
      setUpRegistry();
      assertRejected(Mandatory.class, new Mandatory());

    }

    @Test
    @DisplayName("The JTA @Transactional fails the startup as well")
    public void jakartaTransactional() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.transaction.Transactional
        public void task(
            final Aggregate aggregate) {
        }

      }
      final var e = assertRejected(Service.class, new Service());
      assertTrue(e.getMessage().contains("jakarta.transaction.Transactional"), e.getMessage());
      assertTrue(e.getMessage().contains("dontRollbackOn = TaskException.class"), e.getMessage());

    }

    @Test
    @DisplayName("dontRollbackOn covering TaskException is accepted, also through a superclass")
    public void jakartaDontRollbackOn() {

      class Exact {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.transaction.Transactional(dontRollbackOn = io.vanillabp.spi.service.TaskException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      class Superclass {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.transaction.Transactional(dontRollbackOn = RuntimeException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(Exact.class, new Exact());
      setUpRegistry();
      register(Superclass.class, new Superclass());

    }

    @Test
    @DisplayName("A more specific rollbackFor beats noRollbackFor and fails the startup")
    public void springRollbackForWins() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional(
            rollbackFor = io.vanillabp.spi.service.TaskException.class,
            noRollbackFor = RuntimeException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      assertRejected(Service.class, new Service());

    }

    @Test
    @DisplayName("A more specific rollbackOn beats dontRollbackOn and fails the startup")
    public void jakartaRollbackOnWins() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.transaction.Transactional(
            rollbackOn = io.vanillabp.spi.service.TaskException.class,
            dontRollbackOn = RuntimeException.class)
        public void task(
            final Aggregate aggregate) {
        }

      }
      assertRejected(Service.class, new Service());

    }

    @Test
    @DisplayName("The JTA @Transactional with REQUIRES_NEW is accepted")
    public void jakartaRequiresNew() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
        public void task(
            final Aggregate aggregate) {
        }

      }
      register(Service.class, new Service());

    }

    @Test
    @DisplayName("@TransactionAttribute has no rollback rules: joining fails, REQUIRES_NEW is accepted")
    public void ejbTransactionAttribute() {

      class Joining {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.ejb.TransactionAttribute
        public void task(
            final Aggregate aggregate) {
        }

      }
      class Own {

        @WorkflowTask(taskDefinition = "task")
        @jakarta.ejb.TransactionAttribute(jakarta.ejb.TransactionAttributeType.REQUIRES_NEW)
        public void task(
            final Aggregate aggregate) {
        }

      }
      final var e = assertRejected(Joining.class, new Joining());
      assertTrue(e.getMessage().contains("jakarta.ejb.TransactionAttribute"), e.getMessage());
      // no rollback rules exist on this annotation, so no rule is offered
      assertTrue(e.getMessage().contains("has no rollback rules"), e.getMessage());
      setUpRegistry();
      register(Own.class, new Own());

    }

    @Test
    @DisplayName("An annotation inherited from an interface fails the startup naming the interface")
    public void annotationOnInterface() {

      final var e = assertRejected(InterfaceImplementingService.class, new InterfaceImplementingService());
      assertTrue(e.getMessage().contains("declared on the interface"), e.getMessage());
      assertTrue(e.getMessage().contains(TransactionalWorkflow.class.getName()), e.getMessage());

    }

    @Test
    @DisplayName("An annotation inherited from a superclass fails the startup")
    public void annotationOnSuperclass() {

      final var e = assertRejected(SubclassService.class, new SubclassService());
      assertTrue(e.getMessage().contains(TransactionalBaseService.class.getName()), e.getMessage());

    }

    @Test
    @DisplayName("A custom annotation meta-annotated with @Transactional fails the startup naming it")
    public void metaAnnotation() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @UnitOfWork
        public void task(
            final Aggregate aggregate) {
        }

      }
      final var e = assertRejected(Service.class, new Service());
      assertTrue(e.getMessage().contains(UnitOfWork.class.getName()), e.getMessage());

    }

    @Test
    @DisplayName("An accepted annotation on the method overrides a joining one on the class")
    public void methodOverridesClass() {

      register(MethodOverridesClassService.class, new MethodOverridesClassService());

    }

    @Test
    @DisplayName("An annotation on a method without @WorkflowTask is none of VanillaBP's business")
    public void annotationOnOtherMethod() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        public void task(
            final Aggregate aggregate) {
        }

        @org.springframework.transaction.annotation.Transactional
        public void startTheWorkflow() {
        }

      }
      register(Service.class, new Service());

    }

    @Test
    @DisplayName("javax.transaction.Transactional boots with a WARN: it declares no boundary at all")
    public void obsoleteJavaxAnnotation() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @javax.transaction.Transactional
        public void task(
            final Aggregate aggregate) {
        }

      }
      final var warnings = registerCollectingWarnings(Service.class, new Service());

      assertTrue(
          warnings
              .stream()
              .anyMatch(warning -> warning.contains("javax.transaction.Transactional") && warning
                  .contains("Jakarta namespace")),
          warnings.toString());

    }

    @Test
    @DisplayName("An annotation the platform does not honor only warns, on Quarkus that is Spring's")
    public void annotationNotHonoredByThePlatform() {

      class Service {

        @WorkflowTask(taskDefinition = "task")
        @org.springframework.transaction.annotation.Transactional
        public void task(
            final Aggregate aggregate) {
        }

      }
      // the platform reports Spring's annotation as ineffective, e.g. Quarkus without
      // the extension quarkus-spring-tx: failing the boot over an annotation that does
      // nothing would be wrong
      registry = new WorkflowTaskRegistry(
          new WorkflowTaskRegistryTest.RecordingTransactionRunner(), null, TransactionAnnotationSpecs
              .ofAPlatformWithoutSpringSupport());

      final var warnings = registerCollectingWarnings(Service.class, new Service());

      assertTrue(
          warnings
              .stream()
              .anyMatch(warning -> warning
                  .contains("org.springframework.transaction.annotation.Transactional") && warning
                      .contains("does not map Spring's annotation")),
          warnings.toString());

    }

    @Test
    @DisplayName("All offending methods of a class are reported in one exception")
    public void allOffendingMethodsReported() {

      final var e = assertRejected(TwoOffendingMethodsService.class, new TwoOffendingMethodsService());
      assertTrue(e.getMessage().contains("#first"), e.getMessage());
      assertTrue(e.getMessage().contains("#second"), e.getMessage());

    }

  }

  @org.springframework.transaction.annotation.Transactional
  public static class SpringOnClassService {

    @WorkflowTask(taskDefinition = "task")
    public void task(
        final Aggregate aggregate) {
    }

  }

  @org.springframework.transaction.annotation.Transactional
  public interface TransactionalWorkflow {

    void task(
        Aggregate aggregate);

  }

  public static class InterfaceImplementingService implements TransactionalWorkflow {

    @Override
    @WorkflowTask(taskDefinition = "task")
    public void task(
        final Aggregate aggregate) {
    }

  }

  @org.springframework.transaction.annotation.Transactional
  public static class TransactionalBaseService {

  }

  public static class SubclassService extends TransactionalBaseService {

    @WorkflowTask(taskDefinition = "task")
    public void task(
        final Aggregate aggregate) {
    }

  }

  /**
   * A custom annotation carrying the transaction boundary, a common house style a
   * purely name-based scan would miss.
   */
  @java.lang.annotation.Target({
      java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE
  })
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @org.springframework.transaction.annotation.Transactional
  public @interface UnitOfWork {

  }

  @org.springframework.transaction.annotation.Transactional
  public static class MethodOverridesClassService {

    @WorkflowTask(taskDefinition = "task")
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void task(
        final Aggregate aggregate) {
    }

  }

  @org.springframework.transaction.annotation.Transactional
  public static class TwoOffendingMethodsService {

    @WorkflowTask(taskDefinition = "first")
    public void first(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "second")
    public void second(
        final Aggregate aggregate) {
    }

  }

}
