package io.vanillabp.integration.adapter.migration.delivery;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Deletes the records of processed task deliveries once
 * <code>vanillabp.outbox.retention</code> passed - shared by every
 * {@link io.vanillabp.integration.spi.TaskDeliveryLog} implementation of both platforms,
 * since a store differs in HOW it deletes, not in WHEN.
 * <p>
 * The cleanup runs on a private daemon thread, so neither Spring's
 * {@code TaskScheduler} nor the <code>quarkus-scheduler</code> extension is required.
 * It runs once at startup and then {@value #INTERVAL_HOURS}-hourly: records are kept
 * for days, so nothing is gained by looking more often. Deleting is idempotent - the
 * instances of a cluster may run it concurrently.
 */
@Slf4j
public class TaskDeliveryRetentionCleanup {

  /**
   * The fixed delay between two cleanup runs, in hours.
   */
  public static final int INTERVAL_HOURS = 1;

  private final String name;

  private final Duration retention;

  private final Runnable cleanup;

  private ScheduledExecutorService executor;

  /**
   * @param name Names the store cleaned up (thread name and log messages)
   * @param retention How long a record is kept
   * @param cleanup Deletes the expired records of one store
   */
  public TaskDeliveryRetentionCleanup(
      final String name,
      final Duration retention,
      final Runnable cleanup) {

    this.name = name;
    this.retention = retention;
    this.cleanup = cleanup;

  }

  /**
   * Starts the cleanup. Calling it twice is a no-op - the platforms start it from
   * their own lifecycle hooks.
   */
  public synchronized void start() {

    if (executor != null) {
      return;
    }
    log.debug("Cleaning up task-delivery records of '{}' older than {}", name, retention);
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-task-deliveries");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::runCleanup,
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

  private void runCleanup() {

    try {
      cleanup.run();
    } catch (final RuntimeException e) {
      // a failing cleanup costs disk space, nothing else - it must not kill the
      // scheduled task (a scheduleWithFixedDelay stops on an escaping exception)
      log.warn("Could not clean up expired task-delivery records of '{}'", name, e);
    }

  }

}
