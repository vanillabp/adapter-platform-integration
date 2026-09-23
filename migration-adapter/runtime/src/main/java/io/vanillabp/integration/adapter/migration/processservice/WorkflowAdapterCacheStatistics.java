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

  /**
   * What every meter of this class is named under. The numbers are about the election, so
   * the prefix says nothing about which cache implementation produced them.
   */
  public static final String METER_PREFIX = "vanillabp.workflow.adapter.cache";

  /**
   * Elections answered by a hint instead of by asking the BPMS. Read against
   * {@link #METER_MISSES}: a cache which answers nothing costs nothing but saves nothing
   * either.
   */
  public static final String METER_HITS = METER_PREFIX
      + ".hits";

  /**
   * Elections which had to walk the prioritized adapters. A miss is normal for a workflow
   * nobody asked about yet, so the number only means something over time.
   */
  public static final String METER_MISSES = METER_PREFIX
      + ".misses";

  /**
   * Hints marked as belonging to a workflow which ended. It says whether the end of a
   * workflow reaches the cache at all, which is the first thing to look at when the hints
   * seem to be kept too long.
   */
  public static final String METER_ENDED_MARKS = METER_PREFIX
      + ".ended.marks";

  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

  private final LongAdder endedMarks = new LongAdder();

  /**
   * Built by the platform integration as one bean per application, and handed to every
   * {@link InstrumentedWorkflowAdapterCache}. The counters start at zero, so an
   * application which elects nothing reports three zeros rather than no meter at all.
   */
  public WorkflowAdapterCacheStatistics() {

  }

  /**
   * Read by whoever publishes these numbers, which may be a metrics backend collecting
   * every few seconds, so this is a sum and never a walk over a cache.
   *
   * @return How often a hint answered an election since this application started
   */
  public long getHits() {

    return hits.sum();

  }

  /**
   * The counterpart of {@link #getHits()}. Both together are how often the election was
   * asked at all.
   *
   * @return How often an election found no hint and had to ask the BPMS
   */
  public long getMisses() {

    return misses.sum();

  }

  /**
   * A number which stays at zero while workflows do end says that the end never reaches the
   * cache, which is a different defect from a cache which holds too little.
   *
   * @return How often the end of a workflow was reported to the cache
   */
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
