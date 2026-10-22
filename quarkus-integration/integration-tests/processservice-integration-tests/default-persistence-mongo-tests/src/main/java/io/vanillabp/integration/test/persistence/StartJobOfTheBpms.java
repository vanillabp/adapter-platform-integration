package io.vanillabp.integration.test.persistence;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.spi.service.BpmsStartTrigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Plays the BPMS for the test about the two writers of the start row. The instance of a
 * workflow gets the id <code>wf-&lt;aggregate&gt;</code>. And the start job reaches the start
 * handler of the core at the moment the test asks for, as Camunda 8 hands it to its worker
 * right after the start.
 */
@ApplicationScoped
public class StartJobOfTheBpms implements DummyPhaseTwoListener {

  /**
   * In which order the dispatch of the start and the start handler meet.
   */
  public enum Order {
    /** The start handler is not called. */
    NONE,
    /** The start handler runs and commits while the dispatch is still in phase two. */
    HANDLER_FIRST,
    /** The dispatch wrote its row, and the start handler runs before the dispatch commits. */
    DISPATCH_FIRST,
    /** The start handler runs on its own thread, and nobody waits for anybody. */
    FREE_RUNNING
  }

  @Inject
  TransactionSynchronizationRegistry synchronizations;

  private volatile Order order = Order.NONE;

  private volatile DummyDeploymentService deploymentService;

  private final Map<String, Throwable> handlerFailures = new ConcurrentHashMap<>();

  private final Map<String, Boolean> handlerDone = new ConcurrentHashMap<>();

  public void meetIn(
      final Order order,
      final DummyDeploymentService deploymentService) {

    this.order = order;
    this.deploymentService = deploymentService;

  }

  public boolean handlerIsDone(
      final String workflowAggregateId) {

    return Boolean.TRUE.equals(handlerDone.get(workflowAggregateId));

  }

  public Throwable handlerFailureOf(
      final String workflowAggregateId) {

    return handlerFailures.get(workflowAggregateId);

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    switch (order) {
      case HANDLER_FIRST -> join(callTheStartHandler(workflowAggregateId), Duration.ofSeconds(20));
      case DISPATCH_FIRST -> synchronizations.registerInterposedSynchronization(new Synchronization() {

        @Override
        public void beforeCompletion() {

          // the row of the dispatch is written, and its transaction is still open. A handler
          // which has to wait for it would wait forever, so it gets a moment and then the
          // dispatch commits
          join(callTheStartHandler(workflowAggregateId), Duration.ofSeconds(2));

        }

        @Override
        public void afterCompletion(
            final int status) {
        }

      });
      case FREE_RUNNING -> callTheStartHandler(workflowAggregateId);
      default -> {
      }
    }

  }

  @Override
  public String workflowIdOf(
      final String adapterId,
      final Object workflowAggregateId) {

    return order == Order.NONE
        ? null
        : "wf-"
            + workflowAggregateId;

  }

  private Thread callTheStartHandler(
      final Object workflowAggregateId) {

    final var id = String.valueOf(workflowAggregateId);
    final var thread = new Thread(() -> {
      try {
        deploymentService.startWorkflowByBpms("test-module", "TestProcess", startOf(id));
      } catch (final Throwable e) {
        handlerFailures.put(id, e);
      } finally {
        handlerDone.put(id, Boolean.TRUE);
      }
    }, "start-job-"
        + id);
    thread.start();
    return thread;

  }

  private static void join(
      final Thread thread,
      final Duration atMost) {

    try {
      thread.join(atMost.toMillis());
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }

  /**
   * The start as Camunda 8 reports it: on a worker thread, with the instance key, and with
   * the id of the aggregate in its process variable.
   */
  private BpmsInitiatedStartContext startOf(
      final String workflowAggregateId) {

    final var adapterId = deploymentService.getAdapterId();
    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return "StartEvent";
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return BpmsStartTrigger.Kind.NONE;
      }

      @Override
      public Map<String, Object> getVariables() {
        return Map.of("id", workflowAggregateId);
      }

      @Override
      public String getNativeInstanceId() {
        return "wf-"
            + workflowAggregateId;
      }

      @Override
      public String getAdapterId() {
        return adapterId;
      }

    };

  }

}
