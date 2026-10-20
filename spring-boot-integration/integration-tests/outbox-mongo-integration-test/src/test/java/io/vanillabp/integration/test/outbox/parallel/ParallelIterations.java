package io.vanillabp.integration.test.outbox.parallel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mongodb.MongoException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Plays the BPMS of the parallel multi-instance tests. It delivers tasks for the aggregate
 * which {@link ParallelScenario} stored, and it says what came out of them: how many
 * completed, what the failed ones threw, and what VanillaBP reported about conflicts.
 * <p>
 * Each delivery happens once. A failed delivery is not repeated, so a test which expects no
 * failure also proves that the BPMS would have nothing to repeat.
 */
@Component
public class ParallelIterations {

  private static final String MODULE = "test-module";

  private final DummyDeploymentService dummyAdapter;

  public ParallelIterations(
      final DummyDeploymentService dummyAdapter) {

    this.dummyAdapter = dummyAdapter;

  }

  /**
   * Delivers all iterations at the same time, each on a thread of its own, as a BPMS does
   * with a parallel multi-instance.
   *
   * @param bpmnProcessId The BPMN process of the aggregate
   * @param taskDefinition The task each iteration runs
   * @param iterations How many iterations to deliver
   * @return What came out
   * @throws Exception If a thread of the test itself failed
   */
  public Run run(
      final String bpmnProcessId,
      final String taskDefinition,
      final int iterations) throws Exception {

    final var completed = new AtomicInteger();
    final var failures = Collections.synchronizedList(new ArrayList<Throwable>());
    final var messages = reportedWhile(() -> {
      try (var executor = Executors.newFixedThreadPool(iterations)) {
        final var deliveries = IntStream
            .range(0, iterations)
            .mapToObj(index -> executor.submit(() -> {
              try {
                final var outcome = dummyAdapter
                    .invokeTask(MODULE, bpmnProcessId, iteration(taskDefinition, index, iterations));
                if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETED) {
                  completed.incrementAndGet();
                }
              } catch (final RuntimeException failure) {
                failures.add(failure);
              }
            }))
            .toList();
        for (final Future<?> delivery : deliveries) {
          delivery.get();
        }
      }
    });
    return new Run(completed.get(), List.copyOf(failures), messages);

  }

  /**
   * Delivers one task, not as part of a multi-instance.
   *
   * @param bpmnProcessId The BPMN process of the aggregate
   * @param taskDefinition The task to run
   * @return What came out
   * @throws Exception If the test itself failed
   */
  public Run runOnce(
      final String bpmnProcessId,
      final String taskDefinition) throws Exception {

    final var completed = new AtomicInteger();
    final var failures = new ArrayList<Throwable>();
    final var messages = reportedWhile(() -> {
      try {
        final var outcome = dummyAdapter
            .invokeTask(MODULE, bpmnProcessId, iteration(taskDefinition, -1, 0));
        if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETED) {
          completed.incrementAndGet();
        }
      } catch (final RuntimeException failure) {
        failures.add(failure);
      }
    });
    return new Run(completed.get(), List.copyOf(failures), messages);

  }

  private List<String> reportedWhile(
      final Work work) throws Exception {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var logger = (Logger) LoggerFactory.getLogger(AggregateWrite.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event
            .getLevel()
            .isGreaterOrEqual(Level.WARN))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  /**
   * The context of one delivery. An index below zero means a task outside of a
   * multi-instance.
   */
  private static TaskInvocationContext iteration(
      final String taskDefinition,
      final int index,
      final int total) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return "aggregate";
      }

      @Override
      public Map<String, MultiInstanceValue> getMultiInstances() {
        final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
        if (index >= 0) {
          multiInstances.put("Iterations", new MultiInstanceValue(null, index, total));
        }
        return multiInstances;
      }

    };

  }

  @FunctionalInterface
  private interface Work {

    void run() throws Exception;

  }

  /**
   * What came out of one run of all iterations.
   *
   * @param completed How many deliveries completed
   * @param failures What the deliveries which failed threw at the adapter
   * @param messages The warnings and errors about a version conflict
   */
  public record Run(
                    int completed,
                    List<Throwable> failures,
                    List<String> messages) {

    /**
     * Each failure with its chain of causes, the MongoDB error code where there is one, and
     * the messages. A failed assertion prints this, so a red run says what arrived.
     *
     * @return The description
     */
    public String describe() {

      return failures
          .stream()
          .map(Run::chainOf)
          .collect(Collectors.joining("\n", "completed: %d\nfailures:\n".formatted(completed), "\nmessages: %s"
              .formatted(messages)));

    }

    private static String chainOf(
        final Throwable failure) {

      final var chain = new ArrayList<String>();
      var candidate = failure;
      while (candidate != null) {
        chain
            .add(candidate instanceof MongoException mongoException
                ? "%s(code %d, labels %s)"
                    .formatted(candidate.getClass().getName(), mongoException.getCode(), mongoException
                        .getErrorLabels())
                : candidate.getClass().getName());
        candidate = candidate.getCause() == candidate
            ? null
            : candidate.getCause();
      }
      return String.join(" <- ", chain);

    }

  }

}
