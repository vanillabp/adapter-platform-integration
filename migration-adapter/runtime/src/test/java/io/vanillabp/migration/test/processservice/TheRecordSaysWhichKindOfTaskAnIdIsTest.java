package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.migration.test.RecordedPhaseOperations;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * Completing a task with the id of a user task is answered with what the id IS.
 * <p>
 * The mistake is an ordinary one: <code>completeTask</code> is called with the key of a user
 * task, the BPMS reads that key as a job key, no job carries it, and the answer is "not
 * found". What the developer used to read was a list of three things it could have been -
 * the id is wrong, the task was completed long ago, the workflow was canceled - and not one
 * of them was true. VanillaBP knows better than that: it wrote a record when the handler of
 * that user task ran, and the record says which kind of task the id belongs to.
 * <p>
 * Two things have to happen for that answer to come out. The record must not ELECT an adapter
 * for a command of the other kind, because such a command reaches the BPMS which really holds
 * the task and fails there, at dispatch time, with nobody left to tell. And the failure which
 * follows reads the record again and names the method which asks about the kind the id is.
 * <p>
 * Where nothing was written down, nothing is claimed: an id no record knows gets the list it
 * always got, plus the one thing which explains the silence - a record does not outlive the
 * retention.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheRecordSaysWhichKindOfTaskAnIdIsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c8";

  private static final String AGGREGATE = "4711";

  /** The id of a user task, which is what the mistake hands to a task operation. */
  private static final String USER_TASK = "user-task-1";

  /** The id of a task, which is what the mistake hands to a user-task operation. */
  private static final String TASK = "job-1";

  private static final String ERROR_CODE = "PAYMENT_FAILED";

  /**
   * An adapter whose BPMS knows none of the ids asked about, which is the answer every BPMS
   * gives for an id of the other kind. It counts the questions, so a test can tell "the
   * record answered" from "nobody knew".
   */
  static class NothingIsKnownHere implements MigratableProcessService<Object> {

    final AtomicInteger probes = new AtomicInteger();

    final RecordedPhaseOperations<Object> operations = new RecordedPhaseOperations<>();

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      return operations.operations();

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public boolean deliversTasksAtLeastOnce() {

      return true;

    }

  }

  /** A delivery log in memory which can be queried by the task, like the stores VanillaBP ships. */
  static class DeliveryLogInMemory implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.ofNullable(records.get(deliveryKey));

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;

    }

    @Override
    public Optional<TaskDelivery> recordOfTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String taskId) {

      return records
          .values()
          .stream()
          .filter(record -> workflowModuleId.equals(record.workflowModuleId()))
          .filter(record -> bpmnProcessId.equals(record.bpmnProcessId()))
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> taskId.equals(record.taskId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .findFirst();

    }

  }

  /** The outbox, reduced to what it is here: what phase two was planned with. */
  static class CapturingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new ArrayList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      return scheduled.add(call);

    }

  }

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private final CapturingOutbox outbox = new CapturingOutbox();

  private final NothingIsKnownHere adapter = new NothingIsKnownHere();

  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {

        return Object.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

      @Override
      public Object save(
          final Object workflowAggregate) {

        return workflowAggregate;

      }

      @Override
      public Object getAggregateId(
          final Object workflowAggregate) {

        return AGGREGATE;

      }

    };

  }

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Object> service() {

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties())
        .aggregatePersistence(persistence())
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return outbox;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {

            return List.of(outbox);

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

  /**
   * The record of a task the handler left open, as the core writes it while the handler
   * runs - naming the kind of task the delivery was about.
   *
   * @param taskId The task the record is about
   * @param kind What the delivering adapter said the id is the id of, or <code>null</code>
   *          where the adapter says nothing
   */
  private void recordOpenTaskOf(
      final String taskId,
      final TaskKind kind) {

    deliveryLog
        .record(
            new TaskDelivery(
                "%s|%s|%s|CREATED|%s".formatted(ADAPTER, MODULE, PROCESS,
                    taskId), ADAPTER, MODULE, PROCESS, AGGREGATE, null, "awaitCompletion", null, taskId, "COMPLETION_PENDING", null, null, Instant
                        .now(), null, kind == null
                            ? null
                            : kind.name()));

  }

  @Test
  @DisplayName("completeTask with the id of a user task says that the id is the id of a user task")
  public void completeTaskWithTheIdOfAUserTask() {

    recordOpenTaskOf(USER_TASK, TaskKind.USER_TASK);

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> service().completeTask(new Object(), USER_TASK));

    assertTrue(
        failure.getMessage().contains("'%s' is the id of a user task".formatted(USER_TASK)),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("asks about a user task is completeUserTask"),
        failure.getMessage());
    assertFalse(
        failure.getMessage().contains("Likely causes"),
        "the record knows what the id is, so nothing is guessed: "
            + failure.getMessage());
    assertTrue(outbox.scheduled.isEmpty(), "nothing is planned for a command no BPMS can serve");

  }

  @Test
  @DisplayName("cancelTask with the id of a user task names cancelUserTask")
  public void cancelTaskWithTheIdOfAUserTask() {

    recordOpenTaskOf(USER_TASK, TaskKind.USER_TASK);

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> service().cancelTask(new Object(), USER_TASK, ERROR_CODE));

    assertTrue(
        failure.getMessage().contains("'%s' is the id of a user task".formatted(USER_TASK)),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("asks about a user task is cancelUserTask"),
        failure.getMessage());

  }

  @Test
  @DisplayName("completeUserTask with the id of a task names completeTask")
  public void completeUserTaskWithTheIdOfATask() {

    recordOpenTaskOf(TASK, TaskKind.TASK);

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> service().completeUserTask(new Object(), TASK));

    assertTrue(
        failure.getMessage().contains("'%s' is the id of a task".formatted(TASK)),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("asks about a task is completeTask"),
        failure.getMessage());

  }

  @Test
  @DisplayName("cancelUserTask with the id of a task names cancelTask")
  public void cancelUserTaskWithTheIdOfATask() {

    recordOpenTaskOf(TASK, TaskKind.TASK);

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> service().cancelUserTask(new Object(), TASK, ERROR_CODE));

    assertTrue(
        failure.getMessage().contains("'%s' is the id of a task".formatted(TASK)),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("asks about a task is cancelTask"),
        failure.getMessage());

  }

  @Test
  @DisplayName("An id no record knows gets the list it always got, and hears why")
  public void anIdNoRecordKnowsGetsTheListItAlwaysGot() {

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> service().completeTask(new Object(), TASK));

    assertTrue(
        failure.getMessage().contains("Likely causes: the task ID is wrong or outdated"),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("keeps no record of this id"),
        "a reader has to learn why the answer is not the sharper one: "
            + failure.getMessage());
    assertTrue(
        failure.getMessage().contains("vanillabp.delivery.retention"),
        "and that a record does not outlive the retention: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A command of the kind the record names is elected from it, and no BPMS is asked")
  public void theRightKindIsStillElectedFromTheRecord() {

    recordOpenTaskOf(TASK, TaskKind.TASK);

    service().completeTask(new Object(), TASK);

    assertEquals(0, adapter.probes.get(), "the record answers the election as it always did");
    assertEquals(1, outbox.scheduled.size(), "phase two is planned as it always was");

  }

  @Test
  @DisplayName("A record of an adapter which does not name the kind elects as it always did")
  public void aRecordWithoutAKindElectsAsItAlwaysDid() {

    recordOpenTaskOf(USER_TASK, null);

    service().completeTask(new Object(), USER_TASK);

    assertEquals(0, adapter.probes.get(), "a record which says nothing contradicts nothing");
    assertEquals(1, outbox.scheduled.size());

  }

}
