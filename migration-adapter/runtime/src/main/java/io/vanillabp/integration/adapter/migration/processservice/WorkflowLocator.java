package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import lombok.extern.slf4j.Slf4j;

/**
 * THE BPMS election for operations on EXISTING workflows: complete or cancel a task or user
 * task, correlate a message. New workflows always start in the first-priority adapter. It
 * finds the BPMS which holds a workflow, or one of its tasks, by probing the prioritized
 * adapters in order. The probe can be plugged in per operation, because it depends on the
 * context. Finding a service task probes {@code awarenessOfTask}. Message correlation probes
 * {@code awarenessOfWorkflow}. User tasks have their own awareness. The walk, the retries
 * and the "never fall back" rule here are shared by all of them.
 * <p>
 * An optional {@link WorkflowAdapterCache} makes the walk shorter. A successful election
 * stores the link from workflow module, BPMN process and aggregate ID to the adapter ID. So
 * do the moments when VanillaBP knows the answer for certain
 * ({@link #remember(Object, String)}: phase two of a start, and every inbound delivery). The
 * end of a workflow is the one delivery which does not refresh the link but MARKS it
 * ({@link #rememberWorkflowEnded(Object, String)}). The hint is still read, so an operation
 * which arrives after the end stays a warned no-op. And it leaves the cache long before the
 * hint of a running workflow would. The next election for the same workflow probes the
 * cached adapter first. Cache entries are HINTS, not truth. A hit whose adapter answers
 * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} for longer than that adapter's
 * {@code workflowVisibilityDelay} falls through to the full walk, which repairs the entry.
 * {@link WorkflowAwareness#BPMS_UNAVAILABLE} on a cached adapter follows the rule below:
 * retry, never fall back. It never falls through, because the unavailable BPMS most probably
 * holds the workflow.
 * <p>
 * <b>Under which BPMN process id a hint is.</b> Under the one which was running when
 * VanillaBP learned the answer. That is not always the id on which the application calls its
 * operations. A task of a secondary process is delivered to the instance of THAT process. So
 * the hint is written under the secondary id, while {@code ProcessService} always addresses
 * the primary one. The write stays as it is. It names the process which was really running,
 * and nothing has to be migrated. The READ looks under every id which the workflow service
 * serves, this instance's own first ({@link #setBpmnProcessIdsToReadUnder(List)}). All of
 * them belong to one workflow aggregate. So the first hint found is a hint about this
 * workflow (see decision 5 in the repository's DECISIONS.md).
 * <p>
 * A hint also turns the meaning of an unknown answer around. An adapter which SHOULD hold the
 * workflow and does not report it is a reason to look again, because an eventually
 * consistent BPMS needs a moment after the start. The same answer without any hint is a
 * workflow which nobody ever heard of, and that fails at once.
 * <p>
 * <b>A hint which the cache does not have.</b> The cache lives in the memory of one node and
 * forgets after a while. But the row written when a workflow started is durable. So a caller
 * which holds that row hands it in ({@link RecordedStart}). Where the cache has no hint, the
 * row is the hint. Its adapter is probed first, and an unknown answer means "not visible
 * yet", just as with a cached hint. The cache keeps the first word, because it is free to
 * read, and an election wrote it more recently than the start. See decision 112 in the
 * repository's DECISIONS.md.
 * <p>
 * <b>What the walk cannot do.</b> It stops at the first {@link WorkflowAwareness#ACTIVE}. So
 * it can only be as right as the answers it gets. Whether a workflow belongs to an adapter is
 * the ADAPTER's question, not this one's. Two adapter ids may address one backend (the
 * migration from one scoping to another). And two workflow modules of one backend may have
 * the same aggregate ID. So neither a task ID nor an aggregate ID tells the core anything.
 * The election contract in {@code MigratableProcessService} writes down that duty.
 * {@code ElectionScopeContractTest} shows both halves: the walk reaches the holder where the
 * adapters answer for their own scope, and it stops at the wrong one where an adapter claims
 * more than it holds.
 * <p>
 * The rules of the walk (see {@code MigratableProcessService}):
 * <ul>
 * <li>{@link WorkflowAwareness#ACTIVE}: this adapter executes the operation, stop
 * probing;</li>
 * <li>{@link WorkflowAwareness#UNKNOWN_TO_BPMS}: ask the next adapter;</li>
 * <li>{@link WorkflowAwareness#COMPLETED}: the subject is known but already done. The
 * operation becomes a no-op, and the caller is told;</li>
 * <li>{@link WorkflowAwareness#BPMS_UNAVAILABLE}: NEVER fall back to the next adapter,
 * because the unavailable BPMS might hold the subject. The walk fails and names the
 * unavailable adapter, at once where nothing may sleep.</li>
 * </ul>
 * <p>
 * <b>Who may sleep, and where.</b> Every caller says how patient the walk may be
 * ({@link Patience}), because the same walk runs in places with very different costs. In
 * phase one it runs INSIDE the caller's database transaction. It holds the connection and
 * the locks on the workflow aggregate. A walk which sleeps there drains the connection pool
 * of every caller at once. That is why phase one asks once and never sleeps. At dispatch
 * time no application transaction is open. A repetition costs an entry another attempt, not
 * a connection. So that is where a BPMS gets a second chance, and where a read model gets the
 * moment it needs (decision 27 in the repository's DECISIONS.md). The moment is taken by
 * giving the entry back with a due time, because the lane which carries it also carries the
 * entries of other workflows. Two callers really sleep, both for the same reason. There is
 * no outbox entry behind them which could ask again later. So an answer they do not wait for
 * is an error which the application sees. One is a read of the viewer and history API. The
 * other is the election which an extension asks for
 * ({@code MigrationProcessService#adapterIdOfWorkflow}). That one is not free like a read.
 * It runs on an application thread, and an extension which reports a change calls it while
 * the transaction of that change is still open. The measurement of what that costs is in
 * {@code migration-adapter/README.md}, under "What an election costs a caller which holds a
 * transaction".
 * <p>
 * This walk rests on two rules, and they are written down where the adapters can read them
 * too. An adapter answers only for its own scope, and the walk never falls back (decision 4
 * in the repository's DECISIONS.md). And a remembered adapter is a hint which is probed, not
 * trusted (decision 5 in the repository's DECISIONS.md).
 */
@Slf4j
public final class WorkflowLocator {

  /**
   * How patient a walk may be. See the class javadoc for why the caller decides this, and
   * not the locator.
   */
  public enum Patience {

    /**
     * Ask every adapter once and never sleep. The answer is needed while the caller's
     * transaction is open.
     */
    NONE,

    /**
     * Repeat an unavailable BPMS a few times, but never wait for a read model to catch up.
     * Two callers ask for this, for different reasons. What a task probe answers is exact. A
     * task which the BPMS does not know stays unknown, however long anybody waits. And the
     * dispatch of a phase-two entry has somebody who asks again. It hands the entry back to
     * the outbox with a short due time, which leaves its thread free for the entries of all
     * the other workflows.
     */
    RETRY_UNAVAILABLE,

    /**
     * Also wait out the {@code workflowVisibilityDelay} of an adapter to which a hint points.
     * The workflow exists, but its BPMS has not made it findable yet. Two callers ask for
     * this, both because nobody repeats what they do. An answer they do not wait for is an
     * error which the application sees. One is a read of the viewer and history API, the
     * other the election which an extension asks for.
     * <p>
     * The second one is the expensive one. It runs on an application thread. An extension
     * which reports a change calls it while the transaction of that change is open. So the
     * connection and the locks on the workflow aggregate are held for as long as the wait
     * lasts. See the class javadoc and the measurement it names.
     */
    WAIT_FOR_VISIBILITY

  }

  /**
   * How often a {@link WorkflowAwareness#BPMS_UNAVAILABLE} probe is retried before the walk
   * fails, where the caller allows retrying at all.
   */
  public static final int UNAVAILABLE_RETRIES = 2;

  /**
   * The delay between {@link WorkflowAwareness#BPMS_UNAVAILABLE} retries.
   */
  public static final long UNAVAILABLE_RETRY_DELAY_MILLIS = 500;

  private final String workflowModuleId;

  private final String bpmnProcessId;

  /**
   * The cache of links from a workflow to its adapter, or <code>null</code> for an election
   * without a cache (a plain walk every time).
   */
  private final WorkflowAdapterCache cache;

  /**
   * The BPMN process ids under which a hint of this workflow may be, this instance's own id
   * first. The process service sets it once the platform integration told it which processes
   * its workflow service serves. Until then the own id is the whole list.
   */
  private volatile List<String> bpmnProcessIdsToReadUnder;

  /**
   * Built by the {@link MigrationProcessService} of this BPMN process, in its constructor. So
   * there is one election per BPMN process, while the whole application shares the cache
   * behind it.
   *
   * @param workflowModuleId The workflow module by which every lookup and every hint is
   *          keyed
   * @param bpmnProcessId The plain BPMN process id of this process service
   * @param cache Where hints are read and written, or <code>null</code> where the
   *          application has no cache at all. Then the walk runs every time. That costs
   *          probes, but never an answer
   */
  public WorkflowLocator(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowAdapterCache cache) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.cache = cache;
    this.bpmnProcessIdsToReadUnder = List.of(bpmnProcessId);

  }

  /**
   * Where a hint of this workflow may be looked for, beyond this instance's own BPMN process
   * id.
   * <p>
   * A hint is written under the id of the process which was running when VanillaBP learned the
   * answer. For a task of a secondary process that is the SECONDARY id. But the application
   * calls its operations on the primary process service. So reading only the own id missed the
   * hint of every workflow in which a secondary process had a hand. And with it the reason to
   * wait for an eventually consistent BPMS.
   *
   * @param bpmnProcessIds The ids to read under, the own id first. <code>null</code> or an
   *          empty list means the own id alone again
   */
  public void setBpmnProcessIdsToReadUnder(
      final List<String> bpmnProcessIds) {

    this.bpmnProcessIdsToReadUnder = (bpmnProcessIds == null) || bpmnProcessIds.isEmpty()
        ? List.of(bpmnProcessId)
        : List.copyOf(bpmnProcessIds);

  }

  /**
   * Records that the given adapter holds the workflow of the given aggregate. It is called
   * where VanillaBP knows this without probing anybody. That is after phase two of a start
   * (the adapter which created the instance is recorded with the outbox entry), and at every
   * inbound delivery (a job for that workflow arrived from that BPMS). Both are exactly the
   * moments shortly before an application works on the workflow. That makes the hint worth
   * having.
   * <p>
   * Without a cache the call does nothing.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type, its serialized
   *        form is the key)
   * @param adapterId The ID of the adapter holding the workflow
   */
  public void remember(
      final Object workflowAggregateId,
      final String adapterId) {

    remember(workflowAggregateId, adapterId, null);

  }

  /**
   * The same, with the BPMS' own id of the workflow where the moment knew one. A delivery
   * carries it, and a start learns it from the adapter which created the instance.
   * <p>
   * It helps the paths which WAIT. An adapter which gets the key can ask its engine directly,
   * instead of searching a read model which has not caught up. The id is a hint like the
   * adapter id. So a cache which drops it costs a slower answer, and nothing else.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type, its serialized
   *        form is the key)
   * @param adapterId The ID of the adapter holding the workflow
   * @param workflowId The BPMS' own id of the workflow or <code>null</code>
   */
  public void remember(
      final Object workflowAggregateId,
      final String adapterId,
      final String workflowId) {

    if ((cache == null) || (workflowAggregateId == null) || (adapterId == null)) {
      return;
    }
    cache.put(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), adapterId, workflowId);

  }

  /**
   * Takes back a hint which this instance wrote. It is for the one caller which learns right
   * afterwards that the hint was about a workflow of somebody else. A delivery is recorded
   * before the aggregate is read, and an application which does not hold that aggregate
   * refuses the delivery.
   * <p>
   * The hint is dropped under this instance's own BPMN process id, because it was written
   * under that id. Without a cache the call does nothing. Dropping a hint which is not there
   * does nothing either.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type, its serialized
   *        form is the key)
   */
  public void forget(
      final Object workflowAggregateId) {

    if ((cache == null) || (workflowAggregateId == null)) {
      return;
    }
    cache.invalidate(workflowModuleId, bpmnProcessId, workflowAggregateId.toString());

  }

  /**
   * What the cache knows about the BPMS' own id of this workflow, looked for under every BPMN
   * process id which this workflow service serves.
   * <p>
   * It is a hint, never an answer. It may be missing, it may be stale, and it says nothing
   * about whether the workflow still runs. Whoever reads it hands it to an adapter as a
   * shortcut. An adapter which cannot use it ignores it.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @return The workflow's id in the BPMS, or <code>null</code> where nothing knows one
   */
  public String rememberedWorkflowId(
      final Object workflowAggregateId) {

    if ((cache == null) || (workflowAggregateId == null)) {
      return null;
    }
    final var hint = hintOf(workflowAggregateId.toString());
    return hint == null
        ? null
        : hint.workflowId();

  }

  /**
   * Records that the workflow of the given aggregate ENDED in the given adapter. The hint is
   * kept and not dropped, because the end is not the last thing which happens to a workflow.
   * A task completed after a timeout, a message correlated by an endpoint which did not learn
   * about the end, an outbox entry dispatched after it, and a read of the viewer API all
   * still ask. With the hint they ask the adapter which held the workflow, and get the warned
   * no-op. Without it they would get a walk which fails once the BPMS has forgotten the
   * instance.
   * <p>
   * The marking changes the lifetime. A hint which can never become useful again should not
   * take up a place for as long as the hint of a running workflow. That place may be in the
   * shared cache of a cluster, on infrastructure which the application pays for. Marking is
   * also why the end no longer REFRESHES the hint. It did that while the end was an inbound
   * delivery like any other.
   * <p>
   * Without a cache the call does nothing.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (any type, its serialized
   *        form is the key)
   * @param adapterId The ID of the adapter which held the ended workflow
   */
  public void rememberWorkflowEnded(
      final Object workflowAggregateId,
      final String adapterId) {

    if ((cache == null) || (workflowAggregateId == null) || (adapterId == null)) {
      return;
    }
    cache.putEnded(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), adapterId);

  }

  /**
   * The outcome of a walk which did not fail. Either an adapter answered
   * {@link WorkflowAwareness#ACTIVE} (execute the operation there), or
   * {@link WorkflowAwareness#COMPLETED} (the operation is a no-op). Or every adapter answered
   * {@link WorkflowAwareness#UNKNOWN_TO_BPMS} (the subject is unknown, and the caller raises
   * the guiding error).
   * <p>
   * An unknown answer means two different things, and {@link #hintedAdapterId()} tells them
   * apart. WITH a hint, somebody has seen this workflow on that adapter. VanillaBP started it
   * there, or got a delivery for it. So "unknown" is a read model which has not caught up, and
   * the operation is planned and not refused. WITHOUT a hint nobody ever saw the workflow.
   * Then it is a wrong id, and the caller has to hear about it at once.
   *
   * @param <A> The aggregate type
   * @param awareness ACTIVE, COMPLETED or UNKNOWN_TO_BPMS (the aggregated verdict)
   * @param adapter The adapter which answered ACTIVE or COMPLETED,
   *        <code>null</code> for UNKNOWN_TO_BPMS
   * @param hintedAdapterId The adapter to which a cache hint pointed, or <code>null</code> if
   *        there was none. It is also <code>null</code> where the hint proved right, because
   *        then the adapter is the answer itself
   */
  public record Location<A>(
                            WorkflowAwareness awareness,
                            MigratableProcessService<A> adapter,
                            String hintedAdapterId) {

    /**
     * Tells the two unknown answers apart: a workflow whose BPMS has not caught up yet, and an
     * id which nobody ever saw. A caller which plans an operation may go ahead in the first
     * case, and has to tell the application about the second one.
     *
     * @return Whether a hint claims this workflow exists although no adapter reports
     *         it (yet)
     */
    public boolean isUnknownButExpected() {

      return (awareness == WorkflowAwareness.UNKNOWN_TO_BPMS) && (hintedAdapterId != null);

    }

  }

  /**
   * Elects the adapter which holds the given workflow. It asks the cache first (if there is
   * one), then probes the given adapters in prioritized order.
   *
   * @param <A> The aggregate type
   * @param prioritizedAdapters The adapters in prioritized order
   * @param probe The operation-specific awareness probe
   * @param workflowAggregateId The ID of the workflow aggregate to which the probed subject
   *        belongs. It is the cache key. It may be in the aggregate's own ID type, because its
   *        serialized form is used
   * @param subject A short description of the probed subject (for example
   *        <code>"task 'x' of workflow aggregate '42'"</code>), used in log and error
   *        messages
   * @param patience How long this caller may take for an answer
   * @return The location, never <code>null</code>
   * @throws IllegalStateException If an adapter reports itself
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE}
   */
  public <A> Location<A> locate(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final Object workflowAggregateId,
      final String subject,
      final Patience patience) {

    return locate(prioritizedAdapters, probe, workflowAggregateId, subject, patience, null);

  }

  /**
   * The same election, with the row written when the workflow started as the hint to use
   * where the cache has none.
   *
   * @param <A> The aggregate type
   * @param prioritizedAdapters The adapters in prioritized order
   * @param probe The operation-specific awareness probe
   * @param workflowAggregateId The ID of the workflow aggregate to which the probed subject
   *        belongs
   * @param subject A short description of the probed subject, used in log and error
   *        messages
   * @param patience How long this caller may take for an answer
   * @param recordedStart What the start row says, or <code>null</code> where there is no row,
   *        or where it names an adapter which is not configured for this workflow
   * @return The location, never <code>null</code>
   * @throws IllegalStateException If an adapter reports itself
   *         {@link WorkflowAwareness#BPMS_UNAVAILABLE}
   */
  public <A> Location<A> locate(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final Object workflowAggregateId,
      final String subject,
      final Patience patience,
      final RecordedStart recordedStart) {

    final var serializedAggregateId = workflowAggregateId == null
        ? null
        : workflowAggregateId.toString();

    final var cachedHint = (cache == null) || (serializedAggregateId == null)
        ? null
        : hintOf(serializedAggregateId);
    final var hint = (cachedHint == null) && (recordedStart != null) && (recordedStart.adapterId() != null)
        ? new Hint(bpmnProcessId, recordedStart.adapterId(), recordedStart.workflowId(), false)
        : cachedHint;

    if (hint != null) {
      final var location = locateViaHint(
          prioritizedAdapters, probe, serializedAggregateId, hint, subject, patience);
      if (location != null) {
        return location;
      }
    }

    return walk(
        prioritizedAdapters,
        probe,
        serializedAggregateId,
        hint == null
            ? null
            : hint.adapterId(),
        subject,
        patience);

  }

  /**
   * What a hint says, together with the BPMN process id under which it was found. The second
   * half matters, because a hint is repaired where it is. Dropping or marking it under the own
   * id instead would leave the entry which is read next time saying the old thing.
   *
   * @param bpmnProcessId The id under which the hint is
   * @param adapterId The adapter to which the hint points
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code>
   * @param isCached Whether the hint came from the cache. Only that kind of hint can be
   *        dropped or repaired where it is
   */
  private record Hint(
                      String bpmnProcessId,
                      String adapterId,
                      String workflowId,
                      boolean isCached) {
  }

  /**
   * What the row written when a workflow started says about it: the adapter which started it,
   * and the BPMS' own id of the workflow. A workflow does not change its BPMS. So the adapter
   * of that row is the one to ask first.
   *
   * @param adapterId The adapter which started the workflow
   * @param workflowId The BPMS' own id of the workflow, or <code>null</code>
   */
  public record RecordedStart(
                              String adapterId,
                              String workflowId) {
  }

  /**
   * What VanillaBP wrote down about a workflow before an election asks any adapter: the row
   * written when the workflow started, and the BPMS' own id of the workflow. An operation and
   * a read look this up the same way. So they elect the same adapter.
   *
   * @param recordedStart What the start row says, or <code>null</code> where there is no row,
   *        or where it names an adapter which is not configured for this workflow
   * @param workflowId The BPMS' own id of the workflow, handed to the probe, or
   *        <code>null</code> where VanillaBP holds none
   */
  public record WrittenDown(
                            RecordedStart recordedStart,
                            String workflowId) {
  }

  /**
   * The hint of the given workflow, looked for under every BPMN process id which this
   * workflow service serves, the own id first. The first one found wins. A workflow is one
   * workflow, whichever of those processes was running when the hint was written.
   */
  private Hint hintOf(
      final String serializedAggregateId) {

    for (final var candidate : bpmnProcessIdsToReadUnder) {
      final var entry = cache
          .hintOf(workflowModuleId, candidate, serializedAggregateId)
          .orElse(null);
      if (entry != null) {
        return new Hint(candidate, entry.adapterId(), entry.workflowId(), true);
      }
    }
    return null;

  }

  /**
   * Probes the adapter to which a hint points, before everybody else. It returns
   * <code>null</code> where the hint led nowhere. Then the caller falls through to the full
   * walk, which elects again and repairs the entry.
   * <p>
   * The hint is NOT dropped when its adapter answers "unknown". That is the answer of a read
   * model which has not caught up. Dropping the hint would also drop exactly the knowledge
   * which lets the dispatch wait for that adapter later.
   */
  private <A> Location<A> locateViaHint(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final Hint hint,
      final String subject,
      final Patience patience) {

    final var cachedAdapterId = hint.adapterId();
    final var cachedAdapter = prioritizedAdapters
        .stream()
        .filter(adapter -> adapter.getAdapterId().equals(cachedAdapterId))
        .findFirst()
        .orElse(null);
    if (cachedAdapter == null) {
      // the cached adapter is no longer part of the prioritized configuration. Drop the hint,
      // and let the full walk elect from the current configuration
      log.debug(
          "Cached adapter '{}' for {} is no longer a prioritized adapter - dropping the hint",
          cachedAdapterId,
          subject);
      if (hint.isCached()) {
        cache.invalidate(workflowModuleId, hint.bpmnProcessId(), serializedAggregateId);
      }
      return null;
    }

    final var awareness = patience == Patience.WAIT_FOR_VISIBILITY
        ? probeUntilVisible(cachedAdapter, probe, subject, hint.workflowId())
        : probeWithRetry(cachedAdapter, probe, subject, patience);
    switch (awareness) {
      case ACTIVE -> {
        if (!hint.isCached() && (cache != null)) {
          // the start row proved right. The next operation reads the cache and skips the row
          cache.put(workflowModuleId, bpmnProcessId, serializedAggregateId, cachedAdapterId, hint.workflowId());
        }
        return new Location<>(awareness, cachedAdapter, null);
      }
      case COMPLETED -> {
        // the workflow ended. The hint is marked and not dropped. So the same adapter answers
        // the next operation on it (a warned no-op), instead of a walk over everybody. And it
        // leaves the cache long before the hint of a running workflow would. It is marked
        // where the hint is, not under this instance's own id. A second entry would leave the
        // one which was read claiming a running workflow
        if (cache != null) {
          cache.putEnded(workflowModuleId, hint.bpmnProcessId(), serializedAggregateId, cachedAdapterId);
        }
        return new Location<>(awareness, cachedAdapter, null);
      }
      case UNKNOWN_TO_BPMS -> {
        // hints are not truth. Ask everybody else before believing that the workflow is
        // nowhere. The hint travels with the answer (see Location)
        log.debug(
            "Adapter '{}' does not report {} - probing the other prioritized adapters",
            cachedAdapterId,
            subject);
        return null;
      }
      case BPMS_UNAVAILABLE -> throw unavailable(cachedAdapter, subject, patience);
    }
    return null;

  }

  private <A> Location<A> walk(
      final List<MigratableProcessService<A>> prioritizedAdapters,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String serializedAggregateId,
      final String hintedAdapterId,
      final String subject,
      final Patience patience) {

    for (final var adapter : prioritizedAdapters) {
      if (adapter.getAdapterId().equals(hintedAdapterId)) {
        // asked already, as the hint
        continue;
      }
      final var awareness = probeWithRetry(adapter, probe, subject, patience);
      switch (awareness) {
        case ACTIVE -> {
          if ((cache != null) && (serializedAggregateId != null)) {
            cache.put(workflowModuleId, bpmnProcessId, serializedAggregateId, adapter.getAdapterId());
          }
          return new Location<>(awareness, adapter, null);
        }
        case COMPLETED -> {
          if ((cache != null) && (serializedAggregateId != null)) {
            // an ended workflow is worth remembering too, for the operation which arrives
            // right after the end. It is marked, so the entry lives only briefly
            cache.putEnded(workflowModuleId, bpmnProcessId, serializedAggregateId, adapter.getAdapterId());
          }
          return new Location<>(awareness, adapter, null);
        }
        case UNKNOWN_TO_BPMS -> log.debug(
            "Adapter '{}' does not know {} - asking the next prioritized adapter",
            adapter.getAdapterId(),
            subject);
        case BPMS_UNAVAILABLE -> throw unavailable(adapter, subject, patience);
      }
    }
    return new Location<>(WorkflowAwareness.UNKNOWN_TO_BPMS, null, hintedAdapterId);

  }

  private <A> IllegalStateException unavailable(
      final MigratableProcessService<A> adapter,
      final String subject,
      final Patience patience) {

    return new IllegalStateException(
        """
            The BPMS of adapter '%s' is unavailable while locating %s! Falling back to another \
            adapter is not allowed (the unavailable BPMS might hold it), so the operation is \
            refused%s. Retry the business operation once the BPMS is reachable again."""
            .formatted(
                adapter.getAdapterId(),
                subject,
                patience == Patience.NONE
                    ? " at once - repeating the question inside your transaction would hold its "
                        + "database connection for an answer which rarely changes that quickly"
                    : " after %d retries".formatted(UNAVAILABLE_RETRIES)));

  }

  /**
   * Probes an adapter which VanillaBP has a reason to believe holds the workflow (a cache
   * hint). An {@link WorkflowAwareness#UNKNOWN_TO_BPMS} answer is asked again, until the
   * adapter's {@code workflowVisibilityDelay} window is used up.
   * <p>
   * An eventually consistent BPMS needs that window before a workflow which it just created
   * shows up in the read model which its probe searches. The ordinary "start a workflow, then
   * correlate the message which lets it continue" runs into it. Two things have to hold
   * before anybody waits. A hint has to claim that the workflow exists. And the caller has to
   * be one which has nobody who asks the question again for it
   * ({@link Patience#WAIT_FOR_VISIBILITY}: a read of the viewer API, or the election which an
   * extension asks for).
   * <p>
   * The window is asked for THIS workflow, with the id which the hint carries. An adapter
   * which knows the workflow by that id can name a shorter window than a newly started
   * workflow needs. An adapter which cannot use the id answers its one window through the
   * default, exactly as before the id travelled.
   *
   * @param workflowId The BPMS' own id of the workflow as the hint kept it, or
   *          <code>null</code> where the hint carried none
   */
  private static <A> WorkflowAwareness probeUntilVisible(
      final MigratableProcessService<A> adapter,
      final Function<MigratableProcessService<A>, WorkflowAwareness> probe,
      final String subject,
      final String workflowId) {

    var awareness = probeWithRetry(adapter, probe, subject, Patience.WAIT_FOR_VISIBILITY);
    if (awareness != WorkflowAwareness.UNKNOWN_TO_BPMS) {
      return awareness;
    }

    final var delay = adapter.workflowVisibilityDelay(workflowId);
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
      awareness = probeWithRetry(adapter, probe, subject, Patience.WAIT_FOR_VISIBILITY);
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
      final String subject,
      final Patience patience) {

    var awareness = probe.apply(adapter);
    var retries = patience == Patience.NONE
        ? 0
        : UNAVAILABLE_RETRIES;
    while ((awareness == WorkflowAwareness.BPMS_UNAVAILABLE) && (retries > 0)) {
      log.warn(
          "The BPMS of adapter '{}' is unavailable while locating {} - retrying in {}ms ({} "
              + "retries left; a caller which allows this keeps its thread meanwhile)",
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
