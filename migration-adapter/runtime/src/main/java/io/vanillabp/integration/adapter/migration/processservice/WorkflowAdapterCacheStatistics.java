package io.vanillabp.integration.adapter.migration.processservice;

import java.util.concurrent.atomic.LongAdder;

/**
 * What the BPMS election asked of this application's cache, whichever cache that is:
 * how often a hint answered, how often none did, and how often the end of a workflow
 * reached the cache at all.
 * <p>
 * One instance per application, written by the
 * {@link InstrumentedWorkflowAdapterCache} decorators of all process services. It
 * counts the same three numbers for VanillaBP's in-memory default, for the
 * implementation VanillaBP ships for Hazelcast and for a cache an application wrote
 * itself, because they are numbers about the ELECTION rather than about a cache: a
 * metric which disappears once somebody plugs in their own cache would surprise
 * exactly the operator who needs it.
 * <p>
 * What an implementation knows about itself is not here and has a name of its own.
 * The in-memory default reports its size and its evictions through
 * {@link InMemoryWorkflowAdapterCacheStatistics} under
 * {@value InMemoryWorkflowAdapterCacheStatistics#METER_PREFIX}, and a cache which
 * lives somewhere else does the same under a prefix of its own. A shared number which
 * only one implementation can produce is worse than no number: it reports NaN or a
 * zero which can never become anything else, and a dashboard built on it shows a
 * cache which looks broken while it works.
 * <p>
 * Metric names are declared here and registered by whoever binds them to a metrics
 * backend (see {@code WorkflowAdapterCacheMeters} for the Micrometer binding).
 */
public class WorkflowAdapterCacheStatistics {

  public static final String METER_PREFIX = "vanillabp.workflow.adapter.cache";

  public static final String METER_HITS = METER_PREFIX
      + ".hits";

  public static final String METER_MISSES = METER_PREFIX
      + ".misses";

  public static final String METER_ENDED_MARKS = METER_PREFIX
      + ".ended.marks";

  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

  private final LongAdder endedMarks = new LongAdder();

  public long getHits() {

    return hits.sum();

  }

  public long getMisses() {

    return misses.sum();

  }

  public long getEndedMarks() {

    return endedMarks.sum();

  }

  /**
   * Counts a hint which was marked as belonging to an ended workflow. Counted for
   * every cache in use, the application's own included: it says how often the end of a
   * workflow reached the cache at all, which is the first thing to look at when the
   * release seems not to work (the end is reported only where somebody asked for it).
   */
  public void recordEndedMark() {

    endedMarks.increment();

  }

  /**
   * Counts a lookup which found a hint.
   */
  public void recordHit() {

    hits.increment();

  }

  /**
   * Counts a lookup which found nothing. Whether that miss was avoidable is a
   * question only the implementation can answer, and the in-memory default answers it
   * for itself (see
   * {@link InMemoryWorkflowAdapterCacheStatistics#recordLookupMiss(String, String, String)}).
   */
  public void recordMiss() {

    misses.increment();

  }

}
