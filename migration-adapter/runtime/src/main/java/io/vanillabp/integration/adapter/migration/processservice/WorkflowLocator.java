package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import lombok.extern.slf4j.Slf4j;

/**
 * Locates the BPMS holding an existing workflow (or one of its tasks) by probing
 * the prioritized adapters in order - the election for operations on EXISTING
 * workflows (new workflows always start in the first-priority adapter). The probe
 * is pluggable per operation because it is context-specific: locating a service
 * task probes {@code awarenessOfTask}, message correlation (story 23) probes
 * {@code awarenessOfWorkflow}, user tasks (story 24) their own awareness.
 * <p>
 * Walk contract (see {@code MigratableProcessService}):
 * <ul>
 * <li>{@link WorkflowAwareness#ACTIVE} - this adapter executes the operation, stop
 * probing;</li>
 * <li>{@link WorkflowAwareness#UNKNOWN_TO_BPMS} - ask the next adapter;</li>
 * <li>{@link WorkflowAwareness#COMPLETED} - the subject is known but already done:
 * the operation becomes a no-op (reported to the caller);</li>
 * <li>{@link WorkflowAwareness#BPMS_UNAVAILABLE} - NEVER falls back to the next
 * adapter (the unavailable BPMS might hold the subject): the probe is retried with
 * a short fixed policy ({@value #UNAVAILABLE_RETRIES} retries,
 * {@value #UNAVAILABLE_RETRY_DELAY_MILLIS}ms apart - deliberately no configuration
 * knob, "optimize late"), then the walk fails naming the unavailable adapter.
 * Probing runs inside the caller's transaction, so the delays keep that
 * transaction open - another reason to keep them short.</li>
 * </ul>
 * Story 25 turns this collaborator into the cached election (registry-less); the
 * plain walk here stays as its fallback path.
 */
@Slf4j
public final class WorkflowLocator {

  /**
   * How often a {@link WorkflowAwareness#BPMS_UNAVAILABLE} probe is retried before
   * the walk fails.
   */
  public static final int UNAVAILABLE_RETRIES = 2;

  /**
   * Delay between {@link WorkflowAwareness#BPMS_UNAVAILABLE} retries.
   */
  public static final long UNAVAILABLE_RETRY_DELAY_MILLIS = 500;

  private WorkflowLocator() {
  }

  /**
   * The outcome of a walk which did not fail: either an adapter answered
   * {@link WorkflowAwareness#ACTIVE} (execute the operation there),
   * {@link WorkflowAwareness#COMPLETED} (the operation is a no-op) or every
   * adapter answered {@link WorkflowAwareness#UNKNOWN_TO_BPMS} (the subject is
   * unknown - the caller raises the guiding error).
   *
   * @param <A> The aggregate type
   * @param awareness ACTIVE, COMPLETED or UNKNOWN_TO_BPMS (the aggregated verdict)
   * @param adapter The adapter which answered ACTIVE or COMPLETED,
   *        <code>null</code> for UNKNOWN_TO_BPMS
   */
  public record Location<A>(
                            WorkflowAwareness awareness,
                            MigratableProcessService<A> adapter) {
  }

  /**
   * Probes the given adapters in prioritized order.
   *
   * @param <A> The aggregate type
   * @param prioritizedAdapters The adapters in prioritized order
   * @param probe The operation-specific awareness probe
   * @param subject A short description of the probed subject (e.g.
   *        <code>"task 'x' of workflow aggregate '42'"</code>) used in log and
   *        error messages
   * @return The location - never <code>null</code>
   * @throws IllegalStateException If an adapter stays
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE} after the retries
   */
  public static <A> Location<A> locate(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject) {

    for (final var adapter : prioritizedAdapters) {
      final var awareness = probeWithRetry(adapter, probe, subject);
      switch (awareness) {
        case ACTIVE, COMPLETED -> {
          return new Location<>(awareness, adapter);
        }
        case UNKNOWN_TO_BPMS -> log.debug(
            "Adapter '{}' does not know {} - asking the next prioritized adapter",
            adapter.getAdapterId(),
            subject);
        case BPMS_UNAVAILABLE -> throw new IllegalStateException(
            """
                The BPMS of adapter '%s' is unavailable while locating %s! Falling back to another \
                adapter is not allowed (the unavailable BPMS might hold it) - the operation was \
                aborted after %d retries. Retry the business operation once the BPMS is reachable \
                again."""
                .formatted(adapter.getAdapterId(), subject, UNAVAILABLE_RETRIES));
      }
    }
    return new Location<>(WorkflowAwareness.UNKNOWN_TO_BPMS, null);

  }

  private static <A> WorkflowAwareness probeWithRetry(
      final MigratableProcessService<A> adapter,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject) {

    var awareness = probe.apply(adapter);
    var retries = UNAVAILABLE_RETRIES;
    while ((awareness == WorkflowAwareness.BPMS_UNAVAILABLE) && (retries > 0)) {
      log.warn(
          "The BPMS of adapter '{}' is unavailable while locating {} - retrying in {}ms ({} "
              + "retries left; the caller's transaction stays open meanwhile)",
          adapter.getAdapterId(),
          subject,
          UNAVAILABLE_RETRY_DELAY_MILLIS,
          retries);
      try {
        Thread.sleep(UNAVAILABLE_RETRY_DELAY_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return WorkflowAwareness.BPMS_UNAVAILABLE;
      }
      awareness = probe.apply(adapter);
      --retries;
    }
    return awareness;

  }

}
