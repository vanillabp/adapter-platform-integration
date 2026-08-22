package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import lombok.extern.slf4j.Slf4j;

/**
 * THE BPMS election for operations on EXISTING workflows (complete/cancel task,
 * user task, message correlation - new workflows always start in the
 * first-priority adapter): locates the BPMS holding a workflow (or one of its
 * tasks) by probing the prioritized adapters in order. The probe is pluggable per
 * operation because it is context-specific: locating a service task probes
 * {@code awarenessOfTask}, message correlation probes {@code awarenessOfWorkflow},
 * user tasks their own awareness - the walk/retry/no-fallback semantics here are
 * shared by all of them.
 * <p>
 * An optional {@link WorkflowAdapterCache} short-cuts the walk: a successful
 * election puts the (workflow module, BPMN process, aggregate ID) &rarr; adapter
 * ID association, and so do the moments VanillaBP knows the answer for certain
 * ({@link #remember(Object, String)}: phase two of a start, and every inbound
 * delivery). The next election for the same workflow probes the cached adapter
 * first. Cache entries are HINTS, not truth - a hit whose adapter answers
 * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} for longer than that adapter's
 * {@code workflowVisibilityDelay} falls through to the full walk and repairs the
 * entry. {@link WorkflowAwareness#BPMS_UNAVAILABLE} on a cached adapter follows
 * the retry-never-fallback contract below (it never falls through - the
 * unavailable BPMS most probably holds the workflow).
 * <p>
 * A hint is also what turns the meaning of an unknown answer around: an adapter
 * which SHOULD hold the workflow and does not report it is a reason to look again
 * (an eventually consistent BPMS needs a moment after the start), while the same
 * answer without any hint is a workflow nobody ever heard of, which fails
 * immediately.
 * <p>
 * <b>What the walk cannot do.</b> It stops at the first
 * {@link WorkflowAwareness#ACTIVE} and is therefore only as right as the answers it
 * gets. Whether a workflow belongs to an adapter is the ADAPTER's question, not this
 * one's: two adapter ids may address one backend (the migration from one scoping to
 * another), and two workflow modules of one backend may carry the same aggregate ID,
 * so neither a task ID nor an aggregate ID tells the core anything. The election
 * contract in {@code MigratableProcessService} is where that duty is written down,
 * and {@code ElectionScopeContractTest} shows both halves: the walk reaching the
 * holder where the adapters answer for their own scope, and stopping at the wrong one
 * where an adapter claims more than it holds.
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

  private final String workflowModuleId;

  private final String bpmnProcessId;

  /**
   * The cache of workflow &rarr; adapter associations or <code>null</code> for an
   * uncached election (plain walk every time).
   */
  private final WorkflowAdapterCache cache;

  public WorkflowLocator(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowAdapterCache cache) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.cache = cache;

  }

  /**
   * Records that the given adapter holds the workflow of the given aggregate,
   * called where VanillaBP knows it without probing anybody: after phase two of a
   * start (the adapter which created the instance is recorded with the outbox
   * entry) and on every inbound delivery (a job for that workflow arrived from
   * that BPMS). Both are exactly the moments shortly before an application
   * operates on the workflow, which is what makes the hint worth having.
   * <p>
   * Without a cache the call does nothing.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type - its
   *        serialized form is the key)
   * @param adapterId The ID of the adapter holding the workflow
   */
  public void remember(
      final Object workflowAggregateId,
      final String adapterId) {

    if ((cache == null) || (workflowAggregateId == null) || (adapterId == null)) {
      return;
    }
    cache.put(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), adapterId);

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
   * Elects the adapter holding the given workflow: consults the cache first (if
   * any), then probes the given adapters in prioritized order.
   *
   * @param <A> The aggregate type
   * @param prioritizedAdapters The adapters in prioritized order
   * @param probe The operation-specific awareness probe
   * @param workflowAggregateId The ID of the workflow aggregate the probed subject
   *        belongs to (the cache key; may be in the aggregate's original ID type -
   *        its serialized form is used)
   * @param subject A short description of the probed subject (e.g.
   *        <code>"task 'x' of workflow aggregate '42'"</code>) used in log and
   *        error messages
   * @return The location - never <code>null</code>
   * @throws IllegalStateException If an adapter stays
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE} after the retries
   */
  public <A> Location<A> locate(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final Object workflowAggregateId,
      final String subject) {

    final var serializedAggregateId = workflowAggregateId == null
        ? null
        : workflowAggregateId.toString();

    if ((cache != null) && (serializedAggregateId != null)) {
      final var location = locateViaCache(
          prioritizedAdapters, probe, serializedAggregateId, subject);
      if (location != null) {
        return location;
      }
    }

    return walk(prioritizedAdapters, probe, serializedAggregateId, subject);

  }

  /**
   * Consults the cache: probes the cached adapter first. Returns
   * <code>null</code> if there is no usable hint or the hint turned out stale -
   * the caller falls through to the full walk (which repairs the entry).
   */
  private <A> Location<A> locateViaCache(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final String subject) {

    final var cachedAdapterId = cache
        .get(workflowModuleId, bpmnProcessId, serializedAggregateId)
        .orElse(null);
    if (cachedAdapterId == null) {
      return null;
    }

    final var cachedAdapter = prioritizedAdapters
        .stream()
        .filter(adapter -> adapter.getAdapterId().equals(cachedAdapterId))
        .findFirst()
        .orElse(null);
    if (cachedAdapter == null) {
      // the cached adapter is no longer part of the prioritized configuration -
      // drop the hint and let the full walk elect from the current configuration
      log.debug(
          "Cached adapter '{}' for {} is no longer a prioritized adapter - dropping the hint",
          cachedAdapterId,
          subject);
      cache.invalidate(workflowModuleId, bpmnProcessId, serializedAggregateId);
      return null;
    }

    final var awareness = probeWithVisibilityDelay(cachedAdapter, probe, subject);
    switch (awareness) {
      case ACTIVE -> {
        return new Location<>(awareness, cachedAdapter);
      }
      case COMPLETED -> {
        // the workflow ended - the hint won't be needed again
        cache.invalidate(workflowModuleId, bpmnProcessId, serializedAggregateId);
        return new Location<>(awareness, cachedAdapter);
      }
      case UNKNOWN_TO_BPMS -> {
        // stale hint (hints are not truth): repair by falling through to the
        // full walk which re-elects and re-populates the cache
        log.debug(
            "Cached adapter '{}' does not know {} - the hint is stale, probing all "
                + "prioritized adapters",
            cachedAdapterId,
            subject);
        cache.invalidate(workflowModuleId, bpmnProcessId, serializedAggregateId);
        return null;
      }
      case BPMS_UNAVAILABLE -> throw unavailable(cachedAdapter, subject);
    }
    return null;

  }

  private <A> Location<A> walk(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final String subject) {

    for (final var adapter : prioritizedAdapters) {
      final var awareness = probeWithRetry(adapter, probe, subject);
      switch (awareness) {
        case ACTIVE -> {
          if ((cache != null) && (serializedAggregateId != null)) {
            cache.put(workflowModuleId, bpmnProcessId, serializedAggregateId, adapter.getAdapterId());
          }
          return new Location<>(awareness, adapter);
        }
        case COMPLETED -> {
          return new Location<>(awareness, adapter);
        }
        case UNKNOWN_TO_BPMS -> log.debug(
            "Adapter '{}' does not know {} - asking the next prioritized adapter",
            adapter.getAdapterId(),
            subject);
        case BPMS_UNAVAILABLE -> throw unavailable(adapter, subject);
      }
    }
    return new Location<>(WorkflowAwareness.UNKNOWN_TO_BPMS, null);

  }

  private <A> IllegalStateException unavailable(
      final MigratableProcessService<A> adapter,
      final String subject) {

    return new IllegalStateException(
        """
            The BPMS of adapter '%s' is unavailable while locating %s! Falling back to another \
            adapter is not allowed (the unavailable BPMS might hold it) - the operation was \
            aborted after %d retries. Retry the business operation once the BPMS is reachable \
            again."""
            .formatted(adapter.getAdapterId(), subject, UNAVAILABLE_RETRIES));

  }

  /**
   * Probes an adapter VanillaBP has a reason to believe holds the workflow (a cache
   * hint): an {@link WorkflowAwareness#UNKNOWN_TO_BPMS} answer is asked again until
   * the adapter's {@code workflowVisibilityDelay} window is used up.
   * <p>
   * That window is what an eventually consistent BPMS needs before a workflow it
   * just created shows up in the read model its probe searches - the ordinary
   * "start a workflow, then correlate the message which lets it continue" runs into
   * it. Waiting only happens HERE, on a hinted adapter: an unknown workflow without
   * a hint stays a fast failure, which is what a wrong ID has to be.
   */
  private static <A> WorkflowAwareness probeWithVisibilityDelay(
      final MigratableProcessService<A> adapter,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject) {

    var awareness = probeWithRetry(adapter, probe, subject);
    if (awareness != WorkflowAwareness.UNKNOWN_TO_BPMS) {
      return awareness;
    }

    final var delay = adapter.workflowVisibilityDelay();
    if ((delay == null) || !delay.isWaiting()) {
      return awareness;
    }

    final var deadline = System.nanoTime() + delay.window().toNanos();
    final var intervalMillis = Math.max(1L, delay.interval().toMillis());
    log.debug(
        "Adapter '{}' should hold {} but does not report it (yet) - asking again for up to {}",
        adapter.getAdapterId(),
        subject,
        delay.window());
    while (System.nanoTime() < deadline) {
      try {
        Thread.sleep(intervalMillis);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return awareness;
      }
      awareness = probeWithRetry(adapter, probe, subject);
      if (awareness != WorkflowAwareness.UNKNOWN_TO_BPMS) {
        log.debug(
            "Adapter '{}' reports {} as {} after waiting for its BPMS to catch up",
            adapter.getAdapterId(),
            subject,
            awareness);
        return awareness;
      }
    }
    log.debug(
        "Adapter '{}' still does not know {} after {} - treating the hint as stale",
        adapter.getAdapterId(),
        subject,
        delay.window());
    return awareness;

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
