package io.vanillabp.loadharness;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Plays the BPMS for the load process, so the whole chain runs: the commit, the outbox row,
 * the poll, the dispatch, the delivery and the next row.
 * <p>
 * The double never delivers work on its own. A test calls {@code invokeTask}, and nothing
 * happens between a phase-two call and the next task. This driver closes that loop. It is a
 * {@link DummyPhaseTwoListener}, so it hears every phase-two call of the double, and on each
 * one it schedules what the BPMS would do next. The application registers it as a bean, like
 * any other hook of the double.
 * <p>
 * The work runs on a bounded number of slots ({@link FixedWork#slots()}). The queue in front
 * of them is bounded as well, but not by its capacity. A workflow runs one step at a time, so
 * it has at most one piece of work waiting. And no more than {@link #WORKFLOWS_PER_SLOT}
 * workflows per slot run at the same time, because a new workflow starts only when an
 * earlier one has ended. A queue which made whoever hands in work wait would block the slots
 * themselves, because a slot hands in the next step of its own workflow.
 */
public class LoadDriver implements DummyPhaseTwoListener {

  private static final Logger log = LoggerFactory.getLogger(LoadDriver.class);

  /**
   * How many workflows per slot run at the same time. A few, so a slot never waits for the
   * outbox of the one workflow it serves.
   */
  public static final int WORKFLOWS_PER_SLOT = 4;

  private final LoadAggregates aggregates;

  private final AtomicInteger runs = new AtomicInteger();

  private volatile Run current;

  /**
   * A driver for the aggregates of the given store.
   *
   * @param aggregates Where the aggregates of the load process live. The driver forgets the
   *          aggregate of a workflow which ended
   */
  public LoadDriver(
      final LoadAggregates aggregates) {

    this.aggregates = aggregates;

  }

  /**
   * Runs the fixed work to its end and says what it cost.
   *
   * @param application The application to drive
   * @param work The work
   * @param timeout How long the work may take before the run gives up. A run which gives up
   *          reports the workflows which did not finish as failed
   * @return What the work cost
   */
  public WorkResult run(
      final LoadApplication application,
      final FixedWork work,
      final Duration timeout) {

    final var run = new Run(runs.incrementAndGet(), application, work);
    current = run;
    final var started = System.nanoTime();
    final var deadline = started + timeout.toNanos();
    try {
      for (var index = 0; index < work.workflows(); index++) {
        if (!run.inFlight.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
          break;
        }
        final var aggregateId = "run%d-workflow%d".formatted(run.number, index);
        run.submit(aggregateId, () -> run.start(aggregateId));
      }
      final var finished = run.done.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      final var wallTime = Duration.ofNanos(System.nanoTime() - started);
      if (!finished) {
        log
            .warn(
                "The load run gave up after {}: {} of {} workflows did not finish",
                timeout,
                run.done.getCount(),
                work.workflows());
      }
      return new WorkResult(
          work, wallTime, Map
              .of(
                  Operation.START, List.copyOf(run.statements.get(Operation.START)),
                  Operation.TASK, List.copyOf(run.statements.get(Operation.TASK)),
                  Operation.COMPLETION, List.copyOf(run.statements.get(Operation.COMPLETION)),
                  Operation.CORRELATION, List.copyOf(
                      run.statements.get(Operation.CORRELATION))), run.failures.get() + (int) run.done.getCount());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The load run was interrupted", e);
    } finally {
      run.slots.shutdownNow();
      current = null;
    }

  }

  @Override
  public void startedWorkflowPhaseTwo(
      final Object workflowAggregateId) {

    final var run = runOf(workflowAggregateId);
    if (run == null) {
      return;
    }
    run.delay();
    final var aggregateId = String.valueOf(workflowAggregateId);
    run.submit(aggregateId, () -> run.invokeStep(aggregateId, 0));

  }

  @Override
  public void completedTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    final var run = runOf(workflowAggregateId);
    if (run == null) {
      return;
    }
    run.delay();
    final var aggregateId = String.valueOf(workflowAggregateId);
    run.submit(aggregateId, () -> run.correlate(aggregateId));

  }

  @Override
  public void correlatedMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    final var run = runOf(workflowAggregateId);
    if (run == null) {
      return;
    }
    run.delay();
    run.finish(String.valueOf(workflowAggregateId));

  }

  /**
   * The run which a phase-two call belongs to. A call for a workflow of an earlier run, or
   * a second call for a workflow which ended, belongs to none and is ignored.
   */
  private Run runOf(
      final Object workflowAggregateId) {

    final var run = current;
    if ((run == null) || !run.running.containsKey(String.valueOf(workflowAggregateId))) {
      return null;
    }
    return run;

  }

  /**
   * The operations whose statements the driver counts.
   */
  public enum Operation {

    /**
     * A workflow start, in the application's transaction.
     */
    START,

    /**
     * The delivery of a service task, in the transaction which VanillaBP opens for it.
     */
    TASK,

    /**
     * The completion of a task which the application completes later, in the application's
     * transaction.
     */
    COMPLETION,

    /**
     * The correlation of a message, in the application's transaction.
     */
    CORRELATION

  }

  /**
   * What a fixed work cost.
   *
   * @param work The work
   * @param wallTime How long it took, from the first start to the end of the last workflow
   * @param statements The statements each operation ran, one number per operation
   * @param failedWorkflows Workflows which failed or did not finish in time. A run with a
   *          failed workflow did not pass, however fast it was
   */
  public record WorkResult(
                           FixedWork work,
                           Duration wallTime,
                           Map<Operation, List<Long>> statements,
                           int failedWorkflows) {
  }

  private final class Run {

    private final int number;

    private final LoadApplication application;

    private final FixedWork work;

    private final ThreadPoolExecutor slots;

    private final CountDownLatch done;

    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    private final Map<Operation, Queue<Long>> statements = new ConcurrentHashMap<>();

    private final AtomicInteger failures = new AtomicInteger();

    private final Semaphore inFlight;

    private Run(
        final int number,
        final LoadApplication application,
        final FixedWork work) {

      this.number = number;
      this.application = application;
      this.work = work;
      this.done = new CountDownLatch(work.workflows());
      this.inFlight = new Semaphore(work.slots() * WORKFLOWS_PER_SLOT);
      for (final var operation : Operation.values()) {
        statements.put(operation, new ConcurrentLinkedQueue<>());
      }
      final var threads = new AtomicInteger();
      this.slots = new ThreadPoolExecutor(
          work.slots(), work.slots(), 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
            final var thread = new Thread(runnable, "load-slot-%d-%d".formatted(number, threads.incrementAndGet()));
            thread.setDaemon(true);
            return thread;
          });

    }

    private void submit(
        final String aggregateId,
        final Runnable action) {

      slots.execute(() -> {
        try {
          action.run();
        } catch (RuntimeException e) {
          fail(aggregateId, e);
        }
      });

    }

    private void delay() {

      if (work.phaseTwoDelay().isZero()) {
        return;
      }
      try {
        Thread.sleep(work.phaseTwoDelay());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

    }

    private void start(
        final String aggregateId) {

      running.put(aggregateId, Boolean.TRUE);
      final var counted = StatementCounter
          .count(() -> application
              .inTransaction(() -> application.processService().startWorkflow(new LoadAggregate(aggregateId))));
      statements.get(Operation.START).add(counted.statements());

    }

    private void invokeStep(
        final String aggregateId,
        final int index) {

      final var counted = StatementCounter
          .count(() -> application
              .bpms()
              .invokeTask(application.workflowModuleId(), LoadProcess.BPMN_PROCESS_ID, delivery(
                  aggregateId, LoadProcess.stepElement(index), LoadProcess.STEP)));
      statements.get(Operation.TASK).add(counted.statements());
      if (!completed(aggregateId, counted.result())) {
        return;
      }
      if (index + 1 < work.serviceTasks()) {
        submit(aggregateId, () -> invokeStep(aggregateId, index + 1));
      } else {
        submit(aggregateId, () -> invokeWait(aggregateId));
      }

    }

    private void invokeWait(
        final String aggregateId) {

      final var outcome = application
          .bpms()
          .invokeTask(application.workflowModuleId(), LoadProcess.BPMN_PROCESS_ID, delivery(
              aggregateId, LoadProcess.WAIT_ELEMENT, LoadProcess.WAIT));
      if ((outcome == null) || (outcome.kind() != WorkflowTaskOutcome.Kind.COMPLETION_PENDING)) {
        fail(aggregateId, outcome);
        return;
      }
      submit(aggregateId, () -> complete(aggregateId));

    }

    private void complete(
        final String aggregateId) {

      final var counted = StatementCounter.count(() -> application.inTransaction(() -> {
        final var aggregate = aggregates.loadById(aggregateId);
        return application.processService().completeTask(aggregate, aggregate.getWaitTaskId());
      }));
      statements.get(Operation.COMPLETION).add(counted.statements());

    }

    private void correlate(
        final String aggregateId) {

      final var counted = StatementCounter.count(() -> application.inTransaction(() -> application
          .processService()
          .correlateMessage(aggregates.loadById(aggregateId), LoadProcess.MESSAGE)));
      statements.get(Operation.CORRELATION).add(counted.statements());

    }

    private void finish(
        final String aggregateId) {

      if (running.remove(aggregateId) != null) {
        aggregates.forget(aggregateId);
        done.countDown();
        inFlight.release();
      }

    }

    private boolean completed(
        final String aggregateId,
        final WorkflowTaskOutcome outcome) {

      if ((outcome != null) && (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETED)) {
        return true;
      }
      fail(aggregateId, outcome);
      return false;

    }

    private void fail(
        final String aggregateId,
        final Object reason) {

      if (reason instanceof Throwable failure) {
        log.warn("The load work of workflow '{}' failed", aggregateId, failure);
      } else {
        log.warn("A task of workflow '{}' ended with {}", aggregateId, reason);
      }
      failures.incrementAndGet();
      if (running.remove(aggregateId) != null) {
        done.countDown();
        inFlight.release();
      }

    }

    private TaskInvocationContext delivery(
        final String aggregateId,
        final String elementId,
        final String taskDefinition) {

      final var deliveryId = "%s/%s".formatted(aggregateId, elementId);
      final var adapterId = application.bpms().getAdapterId();
      return new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {

          return taskDefinition;

        }

        @Override
        public String getWorkflowAggregateId() {

          return aggregateId;

        }

        @Override
        public String getBpmnElementId() {

          return elementId;

        }

        @Override
        public String getTaskId() {

          return deliveryId;

        }

        @Override
        public String getDeliveryId() {

          return deliveryId;

        }

        @Override
        public String getAdapterId() {

          return adapterId;

        }

      };

    }

  }

}
