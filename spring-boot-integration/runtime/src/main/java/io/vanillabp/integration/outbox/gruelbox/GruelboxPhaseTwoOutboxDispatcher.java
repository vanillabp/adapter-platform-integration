package io.vanillabp.integration.outbox.gruelbox;

import java.util.concurrent.ScheduledFuture;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.outbox.PhaseTwoOutboxProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Background processing of the gruelbox transaction outbox: right after a commit
 * gruelbox dispatches the scheduled call itself, but for crash recovery and retries a
 * fixed-delay poller calling {@link TransactionOutbox#flush()} is required. The poller
 * is started on {@link ApplicationReadyEvent} (the first run also dispatches entries
 * left over from a previous crashed instance) and uses the poll interval configured by
 * <code>vanillabp.outbox.poll-interval</code>.
 */
@RequiredArgsConstructor
@Slf4j
public class GruelboxPhaseTwoOutboxDispatcher {

  private final TransactionOutbox transactionOutbox;

  private final TaskScheduler taskScheduler;

  private final PhaseTwoOutboxProperties properties;

  private ScheduledFuture<?> poller;

  /**
   * Starts the fixed-delay poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller = taskScheduler.scheduleWithFixedDelay(
        this::flush,
        properties.getPollInterval());

  }

  @PreDestroy
  public void stopPolling() {

    if (poller != null) {
      poller.cancel(false);
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
