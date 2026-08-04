package io.vanillabp.integration.outbox.gruelbox;

import com.gruelbox.transactionoutbox.Submitter;
import com.gruelbox.transactionoutbox.TransactionOutboxEntry;

/**
 * Bridges gruelbox's per-entry state to the dispatch bean: gruelbox invokes the
 * scheduled method with the persisted arguments only - the
 * {@link TransactionOutboxEntry} (and with it the attempts count) is not visible
 * at the invocation. This {@link Submitter} wrapper is the one gruelbox hook
 * carrying the entry BEFORE its invocation: it records &quot;this entry was
 * dispatched before&quot; in a {@link ThreadLocal} read by
 * {@link GruelboxPhaseTwoOutboxAutoConfiguration}'s dispatch bean on the same
 * thread, feeding the START re-dispatch mitigation of the core.
 * <p>
 * Known limitation (accepted): gruelbox increments the attempts count only when
 * an attempt FAILS - after a hard crash between a successful BPMS call and
 * committing the processed flag, the recovered entry still carries
 * <code>attempts == 0</code> and is re-dispatched without the mitigation probe
 * (the documented at-least-once residual). Failure-retries - including the
 * classic &quot;BPMS call succeeded but recording the completion failed&quot; -
 * are detected, also across restarts (the count is persisted).
 */
public final class GruelboxRedispatchAwareSubmitter implements Submitter {

  private static final ThreadLocal<Boolean> PREVIOUSLY_ATTEMPTED = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private final Submitter delegate;

  public GruelboxRedispatchAwareSubmitter(
      final Submitter delegate) {

    this.delegate = delegate;

  }

  /**
   * @return Whether the entry dispatched on the current thread was attempted
   *         before (a retried entry)
   */
  public static boolean isPreviouslyAttempted() {

    return PREVIOUSLY_ATTEMPTED.get();

  }

  @Override
  public void submit(
      final TransactionOutboxEntry entry,
      final java.util.function.Consumer<TransactionOutboxEntry> localExecutor) {

    delegate.submit(
        entry,
        entryOnWorkerThread -> {
          PREVIOUSLY_ATTEMPTED.set(
              (entryOnWorkerThread.getAttempts() > 0) || (entryOnWorkerThread.getLastAttemptTime() != null));
          try {
            localExecutor.accept(entryOnWorkerThread);
          } finally {
            PREVIOUSLY_ATTEMPTED.remove();
          }
        });

  }

}
