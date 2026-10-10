package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException.StartRecord;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A task arrives for a workflow aggregate this application does not store. Either another
 * application on the same BPMS owns the workflow, or this application owned it and its aggregate
 * was deleted. The row written when a workflow starts tells the two apart, so the refusal names
 * the situation the row points at. Where no such row can be read, it names both, as it always
 * did.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheRefusalOfAnUnknownWorkflowReadsTheStartRowTest {

  private static final String MODULE = "refusal-module";

  private static final String PROCESS = "RefusalProcess";

  private static final String ADAPTER = "c8";

  private static final String AGGREGATE = "42";

  private static final String WORKFLOW = "2251799813685249";

  /** A delivery log in memory which answers by key, which is all the start row needs. */
  static class DeliveryLogInMemory implements TaskDeliveryLog {

    final Map<String, TaskDelivery> rows = new HashMap<>();

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.ofNullable(rows.get(deliveryKey));

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return rows.putIfAbsent(delivery.deliveryKey(), delivery) == null;

    }

  }

  /** Runs the work where it is asked for, since this test has no transaction to open. */
  static class RunItRightHere implements TransactionRunner {

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

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  @Test
  @DisplayName("A start row naming this workflow says the aggregate was deleted here, and nothing about another application")
  public void aStartRowOfThisWorkflowNamesTheDeletedAggregate() {

    deliveryLog
        .recordWorkflowStart(TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, WORKFLOW, Instant.now()));

    final var refusal = refusalOf(processService(deliveryLog), WORKFLOW);
    final var message = refusal.getMessage();

    assertEquals(StartRecord.STARTED_HERE, refusal.getStartRecord());
    assertTrue(
        message.startsWith("This application was given a task of a workflow whose workflow aggregate is gone."),
        message);
    assertTrue(message.contains("This application started this workflow"), message);
    assertTrue(message.contains("the workflow aggregate was deleted while the workflow was still running"), message);
    assertFalse(message.contains("does not own"), message);
    assertFalse(message.contains("name-clash-avoidance"), message);
    assertTrue(message.endsWith("so the BPMS still holds the task."), message);

  }

  @Test
  @DisplayName("No start row says another application most likely owns the workflow, and names the case of an old workflow")
  public void noStartRowNamesAnotherApplicationFirst() {

    final var refusal = refusalOf(processService(deliveryLog), WORKFLOW);
    final var message = refusal.getMessage();

    assertEquals(StartRecord.NOT_STARTED_HERE, refusal.getStartRecord());
    assertTrue(message.startsWith("This application was given a task of a workflow it does not own."), message);
    assertTrue(message.contains("most likely another application shares this BPMS"), message);
    assertTrue(
        message.contains("'vanillabp.workflow-modules."
            + MODULE
            + ".adapters."
            + ADAPTER
            + ".name-clash-avoidance: use-prefix'"),
        message);
    // a workflow older than the row can be ours all the same, and the reader is told how to know
    assertTrue(message.contains("'vanillabp.delivery.workflow-start-retention'"), message);
    assertTrue(message.contains("started before VanillaBP 2"), message);
    assertFalse(message.contains("cannot tell them apart"), message);

  }

  @Test
  @DisplayName("A start row naming another workflow of the same aggregate id says nothing, so both situations are named")
  public void aStartRowOfAnotherWorkflowNamesBoth() {

    deliveryLog
        .recordWorkflowStart(
            TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, "2251799813680000", Instant.now()));

    final var refusal = refusalOf(processService(deliveryLog), WORKFLOW);

    assertEquals(StartRecord.NOT_KNOWN, refusal.getStartRecord());
    assertTrue(refusal.getMessage().contains("cannot tell them apart"), refusal.getMessage());

  }

  @Test
  @DisplayName("A delivery naming no workflow cannot be held against a start row, so both situations are named")
  public void aDeliveryWithoutAWorkflowIdNamesBoth() {

    deliveryLog
        .recordWorkflowStart(TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, WORKFLOW, Instant.now()));

    final var refusal = refusalOf(processService(deliveryLog), null);

    assertEquals(StartRecord.NOT_KNOWN, refusal.getStartRecord());
    assertTrue(refusal.getMessage().contains("cannot tell them apart"), refusal.getMessage());

  }

  @Test
  @DisplayName("Without a delivery log nothing can be read, so both situations are named as before")
  public void noDeliveryLogNamesBoth() {

    final var refusal = refusalOf(processService(null), WORKFLOW);

    assertEquals(StartRecord.NOT_KNOWN, refusal.getStartRecord());
    assertTrue(refusal.getMessage().contains("cannot tell them apart"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("1. Another application shares this BPMS"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("2. This application owned the workflow"), refusal.getMessage());

  }

  @Test
  @DisplayName("A delivery log which cannot be read says nothing, so both situations are named")
  public void anUnreadableDeliveryLogNamesBoth() {

    final var broken = new DeliveryLogInMemory() {

      @Override
      public Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        throw new IllegalStateException("the database is away");

      }

    };

    final var refusal = refusalOf(processService(broken), WORKFLOW);

    assertEquals(StartRecord.NOT_KNOWN, refusal.getStartRecord());

  }

  private static DeliveryOfAnUnknownWorkflowException refusalOf(
      final MigrationProcessService<Object> service,
      final String workflowId) {

    final var handler = Mockito.mock(WorkflowTaskHandler.class);
    Mockito.when(handler.acceptsEvent(Mockito.any())).thenReturn(true);
    return assertThrowsExactly(
        DeliveryOfAnUnknownWorkflowException.class,
        () -> service.executeWorkflowTask(handler, deliveryOf(workflowId), new RunItRightHere(), List.of()));

  }

  private static TaskInvocationContext deliveryOf(
      final String workflowId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {

        return ADAPTER;

      }

      @Override
      public String getTaskDefinition() {

        return "someTask";

      }

      @Override
      public String getWorkflowAggregateId() {

        return AGGREGATE;

      }

      @Override
      public String getWorkflowId() {

        return workflowId;

      }

    };

  }

  @SuppressWarnings("unchecked")
  private static MigrationProcessService<Object> processService(
      final TaskDeliveryLog deliveryLog) {

    final var adapter = (MigratableProcessService<Object>) Mockito.mock(MigratableProcessService.class);
    Mockito.when(adapter.getAdapterId()).thenReturn(ADAPTER);
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(new AggregatePersistenceAware<>() {

          @Override
          public Class<Object> getAggregateClass() {

            return Object.class;

          }

          @Override
          public Class<?> getAggregateIdType() {

            return String.class;

          }

          @Override
          public Object loadById(
              final Object id) {

            // the whole point: no workflow aggregate of that id is stored here
            return null;

          }

        })
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return call -> true;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {

            return List.of();

          }

        })
        .taskDeliveryLogResolver(new TaskDeliveryLogResolver() {

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

}
