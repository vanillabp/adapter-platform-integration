package io.vanillabp.integration.adapter.migration.delivery;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes the records of processed task deliveries once
 * <code>vanillabp.delivery.retention</code> has passed. Every
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog} implementation of both platforms
 * shares this class, because a store differs in HOW it deletes, not in WHEN.
 * <p>
 * The cleanup runs on a private daemon thread. So neither the {@code TaskScheduler} of
 * Spring nor the <code>quarkus-scheduler</code> extension is needed. It runs once at startup
 * and then every {@value #INTERVAL_HOURS} hours. Records are kept for days, so looking more
 * often gains nothing. Deleting can be repeated safely, so the instances of a cluster may
 * run it at the same time.
 * <p>
 * An hour in which no delivery was recorded deletes nothing, and so it asks nothing. The
 * records of this store only come into being when the application does work. So an
 * application which waits in a timer grows no garbage, and its database sees no statement
 * from here. What remains is the last batch before an application went quiet. It is kept
 * until the application works again or restarts. Keeping a record LONGER is the safe side
 * of the window it guards (decision 43 in the repository's DECISIONS.md).
 */
@Slf4j
public class TaskDeliveryRetentionCleanup {

  /**
   * The fixed delay between two cleanup runs, in hours.
   */
  public static final int INTERVAL_HOURS = 1;

  private final String name;

  private final Duration retention;

  private final Duration workflowStartRetention;

  private final Runnable cleanup;

  /**
   * Whether anything was written to this store since the last run. A run without that would
   * delete what an earlier run already deleted.
   */
  private final AtomicBoolean recordedSinceTheLastRun = new AtomicBoolean(true);

  private ScheduledExecutorService executor;

  /**
   * Built by the platform's delivery log, which starts it from its own lifecycle hook.
   * <p>
   * Nothing runs yet. The first run happens when {@link #start()} is called. Until then the
   * store counts as written to. So the records of an earlier run of the application are
   * cleaned up, even where this run records nothing.
   *
   * @param name Names the store cleaned up (thread name and log messages)
   * @param retention How long a record is kept
   * @param workflowStartRetention How long the row about a started workflow is kept. This is
   *          a period of its own, and it is logged at startup for the same reason as the
   *          first one
   * @param cleanup Deletes the expired rows of one store, both kinds
   */
  public TaskDeliveryRetentionCleanup(
      final String name,
      final Duration retention,
      final Duration workflowStartRetention,
      final Runnable cleanup) {

    this.name = name;
    this.retention = retention;
    this.workflowStartRetention = workflowStartRetention;
    this.cleanup = cleanup;

  }

  /**
   * Starts the cleanup. A second call does nothing, because the platforms start it from
   * their own lifecycle hooks.
   */
  public synchronized void start() {

    if (executor != null) {
      return;
    }
    // said once and at INFO. Which of the two retentions this store runs with is the first
    // question in a support case about a handler which ran twice
    log
        .info(
            "Records of processed task deliveries in '{}' are kept for {} ('{}'), and what a "
                + "started workflow left behind {} ('{}')",
            name,
            retention,
            DeliveryProperties.RETENTION_PROPERTY,
            (workflowStartRetention == null) || workflowStartRetention.isZero()
                ? "is kept for good"
                : "for %s".formatted(workflowStartRetention),
            DeliveryProperties.WORKFLOW_START_RETENTION_PROPERTY);
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-task-deliveries");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::cleanUpWhereSomethingWasRecorded,
        0,
        Duration.ofHours(INTERVAL_HOURS).toMillis(),
        TimeUnit.MILLISECONDS);

  }

  /**
   * Stops the cleanup on shutdown of the application.
   */
  public synchronized void stop() {

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

  }

  /**
   * Says that a delivery was written down. That gives the next hourly run something to do.
   * The store calls it for every record it writes.
   */
  public void aDeliveryWasRecorded() {

    recordedSinceTheLastRun.set(true);

  }

  /**
   * One run of the cleanup. Where no delivery was recorded since the previous run, it
   * deletes nothing and asks nothing. The scheduled thread calls it. It is public so that a
   * test can drive it without waiting for an hour.
   */
  public void cleanUpWhereSomethingWasRecorded() {

    if (!recordedSinceTheLastRun.getAndSet(false)) {
      return;
    }
    try {
      cleanup.run();
    } catch (final RuntimeException e) {
      // a failing cleanup costs disk space, nothing else. It must not kill the scheduled
      // task, because scheduleWithFixedDelay stops on an escaping exception. What it did not
      // delete is tried again in an hour, not only at the next record
      recordedSinceTheLastRun.set(true);
      log.warn("Could not clean up expired task-delivery records of '{}'", name, e);
    }

  }

}
