package io.vanillabp.integration.outbox.gruelbox;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.outbox.PhaseTwoOutboxProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Background processing of the gruelbox transaction outbox: right after a commit
 * gruelbox dispatches the scheduled call itself, but for crash recovery and retries a
 * fixed-delay poller calling {@link TransactionOutbox#flush()} is required. Flushing
 * also deletes successfully dispatched entries whose retention threshold passed (the
 * asynchronous cleanup of the "DONE instead of delete" contract). The poller is
 * started on {@link ApplicationReadyEvent} (the first run also dispatches entries
 * left over from a previous crashed instance) and uses the poll interval configured by
 * <code>vanillabp.outbox.poll-interval</code>.
 * <p>
 * The poller runs on a private single-thread daemon executor - no
 * {@link org.springframework.scheduling.TaskScheduler} bean is registered or used, so
 * an application's own scheduling setup (e.g. <code>&#64;EnableScheduling</code>)
 * stays unaffected.
 */
@RequiredArgsConstructor
@Slf4j
public class GruelboxPhaseTwoOutboxDispatcher {

  private final TransactionOutbox transactionOutbox;

  private final PhaseTwoOutboxProperties properties;

  private ScheduledExecutorService poller;

  /**
   * Starts the fixed-delay poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-outbox");
      thread.setDaemon(true);
      return thread;
    });
    poller.scheduleWithFixedDelay(
        this::flush,
        0,
        properties.getPollInterval().toMillis(),
        TimeUnit.MILLISECONDS);

  }

  @PreDestroy
  public void stopPolling() {

    if (poller != null) {
      poller.shutdown();
      poller = null;
    }

  }

  /**
   * Flushes the outbox until no more work is done. Exceptions are caught to keep the
   * poller alive.
   */
  private void flush() {

    try {
      //noinspection StatementWithEmptyBody
      while (transactionOutbox.flush()) {
        // repeat until all due outbox entries were processed
      }
    } catch (Exception e) {
      log.error("Flushing the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

}
