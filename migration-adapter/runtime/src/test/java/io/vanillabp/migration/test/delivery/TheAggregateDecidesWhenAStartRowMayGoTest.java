package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.delivery.AggregateBoundWorkflowStarts;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The second sieve in front of deleting a workflow-start row: a period is a guess, and the
 * application's own persistence knows whether anybody can still ask about that aggregate.
 * <p>
 * Every answer but a plain "the aggregate is gone" KEEPS the row, and that is the point of this
 * test: a persistence which cannot be asked must not be read as "gone", because the row it would
 * cost cannot be written again.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheAggregateDecidesWhenAStartRowMayGoTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c8";

  private static final String AGGREGATE = "4711";

  /** An aggregate of the application, which exists or does not. */
  static class AnAggregate {
  }

  /** An adapter which does nothing: this test never reaches a BPMS. */
  static class AnAdapterDoingNothing implements MigratableProcessService<AnAggregate> {

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<AnAggregate>> phaseOperations() {

      return Map.of();

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<AnAggregate> aggregatePersistence,
        final Object workflowAggregateId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

  }

  /**
   * The persistence of the application, which either holds the aggregate, does not hold it, or
   * cannot be asked at all - the last one being a custom implementation which did not override
   * <code>loadById</code>.
   */
  private static AggregatePersistenceAware<AnAggregate> persistenceWhich(
      final java.util.function.Supplier<AnAggregate> loadById) {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<AnAggregate> getAggregateClass() {

        return AnAggregate.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

      @Override
      public AnAggregate loadById(
          final Object aggregateId) {

        return loadById.get();

      }

    };

  }

  private static final TaskDelivery START_ROW = TaskDelivery
      .workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, "instance-4711", Instant.now());

  private static AggregateBoundWorkflowStarts sieveOf(
      final AggregatePersistenceAware<AnAggregate> persistence) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    final var service = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, AnAggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(new AnAdapterDoingNothing()))
        .build();
    final var router = new PhaseTwoRouter();
    router.register(service);
    return new AggregateBoundWorkflowStarts(() -> router, "VANILLABP_TASK_DELIVERY");

  }

  @Test
  @DisplayName("An aggregate which is gone lets its start row go")
  public void anAggregateWhichIsGoneLetsTheRowGo() {

    assertTrue(sieveOf(persistenceWhich(() -> null)).mayBeDeleted(START_ROW));

  }

  @Test
  @DisplayName("An aggregate which still exists keeps its start row")
  public void anAggregateWhichExistsKeepsTheRow() {

    assertFalse(sieveOf(persistenceWhich(AnAggregate::new)).mayBeDeleted(START_ROW));

  }

  @Test
  @DisplayName("A persistence which cannot be asked keeps the row, and says so once")
  public void aPersistenceWhichCannotBeAskedKeepsTheRow() {

    // the default of AggregatePersistenceAware#loadById, which a custom implementation keeps when
    // it does not override the method
    final var sieve = sieveOf(new AggregatePersistenceAware<>() {

      @Override
      public Class<AnAggregate> getAggregateClass() {

        return AnAggregate.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

    });

    final var firstRun = whatIsLoggedBy(() -> assertNull(sieve.mayBeDeleted(START_ROW)));
    final var secondRun = whatIsLoggedBy(() -> assertNull(sieve.mayBeDeleted(START_ROW)));

    assertTrue(
        firstRun.contains("keep-workflow-start-while-aggregate-exists"),
        firstRun);
    assertTrue(firstRun.contains("loadById"), firstRun);
    assertTrue(
        secondRun.isEmpty(),
        "a cleanup running hourly for a year must not say it eight thousand times: "
            + secondRun);

  }

  /**
   * What the sieve wrote to the log while the work ran.
   *
   * @param work The call to the sieve
   * @return Every line it logged, joined
   */
  private static String whatIsLoggedBy(
      final Runnable work) {

    final var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    final var recorded = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    try {
      work.run();
    } finally {
      root.detachAppender(recorded);
    }
    return recorded.list
        .stream()
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .reduce("", (
            all,
            line) -> all
                + line
                + "\n");

  }

  @Test
  @DisplayName("A BPMN process this application does not serve keeps the row")
  public void aProcessNobodyServesKeepsTheRow() {

    final var sieve = sieveOf(persistenceWhich(() -> null));

    assertNull(
        sieve
            .mayBeDeleted(
                TaskDelivery
                    .workflowStart(
                        ADAPTER, MODULE, "AnotherProcess", AGGREGATE, "instance-4712", Instant.now())));

  }

  @Test
  @DisplayName("A sieve without a router keeps the row, which is a cleanup during the start")
  public void aSieveWithoutARouterKeepsTheRow() {

    assertNull(
        new AggregateBoundWorkflowStarts(() -> null, "VANILLABP_TASK_DELIVERY")
            .mayBeDeleted(START_ROW));
    assertEquals(
        null,
        new AggregateBoundWorkflowStarts(null, "VANILLABP_TASK_DELIVERY").mayBeDeleted(START_ROW));

  }

}
