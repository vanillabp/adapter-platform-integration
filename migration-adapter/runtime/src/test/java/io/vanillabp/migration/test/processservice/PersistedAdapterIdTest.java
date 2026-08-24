package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter id which the persisted state still names although the configuration does not.
 * <p>
 * VanillaBP persists the adapter id twice: an outbox entry of a START operation names the
 * adapter elected in phase one, and the delivery key of every record is built from the
 * delivering adapter. An id which is gone therefore has two readings, and both cost the
 * application something - a workflow which was persisted and never started, or a
 * <code>&#64;WorkflowTask</code> method which runs a second time. Both were silent until
 * the entry was dispatched respectively the task was delivered again.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PersistedAdapterIdTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /** Configures and prioritizes exactly one adapter. */
  private static MigrationAdapterProperties properties(
      final List<String> retiredAdapters) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("new-bpms", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("new-bpms"))
        .retiredAdapters(retiredAdapters)
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * A REMOTE adapter: it needs a two-phase commit and may repeat a delivery, which is what
   * makes the platform's own validations resolve both stores - the state this check runs
   * in. An application whose adapters are all embedded persists neither entries nor
   * delivery records, so there is nothing to ask about either.
   */
  private static MigratableProcessService<Object> adapter() {

    return new MigratableProcessService<>() {

      @Override
      public String getAdapterId() {

        return "new-bpms";

      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfWorkflow(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
          final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
          final Object workflowAggregateId,
          final String taskId) {

        return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;

      }

      @Override
      public boolean needsTwoPhaseCommitForStartingWorkflows() {

        return true;

      }

      @Override
      public boolean deliversTasksAtLeastOnce() {

        return true;

      }

      @Override
      public void startWorkflowPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate) {

      }

      @Override
      public void startWorkflowPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId) {

      }

      @Override
      public void completeTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String taskId) {

      }

      @Override
      public void completeTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId) {

      }

      @Override
      public void cancelTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String taskId,
          final String bpmnErrorCode) {

      }

      @Override
      public void cancelTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId,
          final String bpmnErrorCode) {

      }

      @Override
      public void completeUserTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String taskId) {

      }

      @Override
      public void completeUserTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId) {

      }

      @Override
      public void cancelUserTaskPhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String taskId,
          final String bpmnErrorCode) {

      }

      @Override
      public void cancelUserTaskPhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String taskId,
          final String bpmnErrorCode) {

      }

      @Override
      public void correlateMessagePhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String messageName,
          final String correlationId) {

      }

      @Override
      public void correlateMessagePhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String messageName,
          final String correlationId) {

      }

      @Override
      public void startWorkflowByMessagePhaseOne(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregate,
          final String messageName) {

      }

      @Override
      public void startWorkflowByMessagePhaseTwo(
          final String workflowModuleId,
          final String bpmnProcessId,
          final AggregatePersistenceAware<Object> aggregatePersistence,
          final Object workflowAggregateId,
          final String messageName) {

      }

    };

  }

  /** The aggregate's persistence, reduced to what the constructor asks it. */
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

    };

  }

  /** A store answering the given adapter ids, and nothing else. */
  private static PhaseTwoOutbox outboxWaitingFor(
      final Set<String> adapterIds) {

    return new PhaseTwoOutbox() {

      @Override
      public boolean schedule(
          final PhaseTwoCall call) {

        return true;

      }

      @Override
      public Set<String> adapterIdsOfPendingCalls(
          final String workflowModuleId,
          final String bpmnProcessId) {

        assertEquals(MODULE, workflowModuleId, "the store is asked about the calling process service");
        assertEquals(PROCESS, bpmnProcessId, "the store is asked about the calling process service");
        return adapterIds;

      }

    };

  }

  /** A log answering the given adapter ids for its open records, and nothing else. */
  private static TaskDeliveryLog logWithOpenTasksOf(
      final Set<String> adapterIds) {

    return new TaskDeliveryLog() {

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

      @Override
      public Set<String> adapterIdsOfOpenTasks(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return adapterIds;

      }

    };

  }

  /**
   * A process service whose stores are already resolved - which is the state the check
   * runs in: the platform integration calls it after the outbox and the delivery-log
   * validations.
   */
  /**
   * The WARNings the check logged. "Normal" logging is off during tests, so the appender is
   * attached to the class which logs (the same pattern the delivery tests use).
   */
  private static List<String> loggedBy(
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(MigrationProcessService.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
        .map(event -> event.getFormattedMessage())
        .toList();

  }

  private static MigrationProcessService<Object> serviceWith(
      final MigrationAdapterProperties properties,
      final PhaseTwoOutbox outbox,
      final TaskDeliveryLog deliveryLog) {

    final var service = new MigrationProcessService<Object>(
        MODULE, PROCESS, Object.class, properties, persistence(), List
            .of(adapter()), new PhaseTwoOutboxResolver() {

              @Override
              public PhaseTwoOutbox resolveFor(
                  final Class<?> workflowAggregateClass) {

                return outbox;

              }

              @Override
              public String remediesDescription() {

                return "";

              }

            }, null, new io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver() {

              @Override
              public TaskDeliveryLog resolveFor(
                  final Class<?> workflowAggregateClass) {

                return deliveryLog;

              }

              @Override
              public String remediesDescription() {

                return "";

              }

            });
    // resolves both stores the way the platform's own validations do before this check
    service.validatePhaseTwoOutboxAtStartup();
    service.validateTaskDeliveryLogAtStartup();
    return service;

  }

  @Test
  @DisplayName("An id which entries are waiting for and which is not configured is named, with both readings")
  public void anUnconfiguredIdWithWaitingEntriesIsNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of("old-bpms")), logWithOpenTasksOf(Set.of()));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertTrue(output.contains("old-bpms"), output);
    assertTrue(output.contains("RENAMED"), output);
    assertTrue(output.contains("never started"), output);
    assertTrue(output.contains("retired-adapters"), output);
    assertTrue(output.contains(MODULE), output);
    assertTrue(output.contains(PROCESS), output);

  }

  @Test
  @DisplayName("An id which open task records belong to is named with what a rename costs there")
  public void anUnconfiguredIdWithOpenTasksIsNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of()), logWithOpenTasksOf(Set.of("old-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertTrue(output.contains("old-bpms"), output);
    assertTrue(output.contains("still open"), output);
    assertTrue(output.contains("@WorkflowTask method a second time"), output);

  }

  @Test
  @DisplayName("A retired id is not named - the end of a migration stays quiet")
  public void aRetiredIdIsQuiet() {

    final var service = serviceWith(
        properties(List.of("old-bpms")), outboxWaitingFor(Set.of("old-bpms")), logWithOpenTasksOf(
            Set.of("old-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("RENAMED"), output);

  }

  @Test
  @DisplayName("A configured id is never named, however much is waiting for it")
  public void aConfiguredIdIsNeverNamed() {

    final var service = serviceWith(
        properties(List.of()), outboxWaitingFor(Set.of("new-bpms")), logWithOpenTasksOf(Set.of("new-bpms")));

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("new-bpms"), output);

  }

  @Test
  @DisplayName("A store which cannot say leaves the check silent instead of inventing an answer")
  public void aStoreWhichCannotSayIsSilent() {

    // the SPI default of both methods: an empty answer
    final var service = serviceWith(
        properties(List.of()), new PhaseTwoOutbox() {

          @Override
          public boolean schedule(
              final PhaseTwoCall call) {

            return true;

          }

        }, new TaskDeliveryLog() {

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

        });

    final var output = String.join("\n", loggedBy(service::validatePersistedAdapterIdsAtStartup));

    assertFalse(output.contains("RENAMED"), output);

  }

}
