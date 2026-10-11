package io.vanillabp.integration.adapter.migration.processservice;

import java.util.concurrent.atomic.LongAdder;

/**
 * What the BPMS election asked of this application's cache, whichever cache that is: how
 * often a hint answered, how often none did, and how often the end of a workflow reached the
 * cache at all.
 * <p>
 * There is one instance per application. The {@link InstrumentedWorkflowAdapterCache}
 * decorators of all process services write to it. It counts the same three numbers for
 * VanillaBP's in-memory default, for the implementation which VanillaBP ships for
 * Hazelcast, and for a cache which an application wrote itself. They are numbers about the
 * ELECTION, not about a cache. A metric which disappears once somebody plugs in their own
 * cache would surprise exactly the operator who needs it.
 * <p>
 * What an implementation knows about itself is not here, and it has a name of its own. The
 * in-memory default reports its size and its evictions through
 * {@link InMemoryWorkflowAdapterCacheStatistics}, under
 * {@value InMemoryWorkflowAdapterCacheStatistics#METER_PREFIX}. A cache which lives
 * somewhere else does the same under a prefix of its own. A shared number which only one
 * implementation can produce is worse than no number. It reports NaN, or a zero which can
 * never become anything else. And a dashboard built on it shows a cache which looks broken
 * while it works.
 * <p>
 * The metric names are declared here. Whoever binds them to a metrics backend registers
 * them (see {@code WorkflowAdapterCacheMeters} for the Micrometer binding).
 */
public class WorkflowAdapterCacheStatistics {

  /**
   * The prefix of every meter of this class. The numbers are about the election, so the
   * prefix says nothing about which cache implementation produced them.
   */
  public static final String METER_PREFIX = "vanillabp.workflow.adapter.cache";

  /**
   * Elections which a hint answered, instead of asking the BPMS. Read it against
   * {@link #METER_MISSES}. A cache which answers nothing costs nothing, but saves nothing
   * either.
   */
  public static final String METER_HITS = METER_PREFIX
      + ".hits";

  /**
   * Elections which had to walk the prioritized adapters. A miss is normal for a workflow
   * which nobody asked about yet. So the number only means something over time.
   */
  public static final String METER_MISSES = METER_PREFIX
      + ".misses";

  /**
   * Hints marked as belonging to a workflow which ended. It says whether the end of a
   * workflow reaches the cache at all. That is the first thing to look at when the hints seem
   * to be kept too long.
   */
  public static final String METER_ENDED_MARKS = METER_PREFIX
      + ".ended.marks";

  private final LongAdder hits = new LongAdder();

  private final LongAdder misses = new LongAdder();

  private final LongAdder endedMarks = new LongAdder();

  /**
   * Built by the platform integration as one bean per application, and handed to every
   * {@link InstrumentedWorkflowAdapterCache}. The counters start at zero. So an application
   * which elects nothing reports three zeros, and not no meter at all.
   */
  public WorkflowAdapterCacheStatistics() {

  }

  /**
   * Whoever publishes these numbers reads this, and that may be a metrics backend which
   * collects every few seconds. So this is a sum, and never a walk over a cache.
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
   * cache. That is a different defect from a cache which holds too little.
   *
   * @return How often the end of a workflow was reported to the cache
   */
  public long getEndedMarks() {

    return endedMarks.sum();

  }

  /**
   * Counts a hint which was marked as belonging to an ended workflow. It is counted for every
   * cache in use, the application's own included. It says how often the end of a workflow
   * reached the cache at all. That is the first thing to look at when the release seems not
   * to work (the end is reported only where somebody asked for it).
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
   * Counts a lookup which found nothing. Only the implementation can answer whether that
   * miss could have been avoided. The in-memory default answers it for itself (see
   * {@link InMemoryWorkflowAdapterCacheStatistics#recordLookupMiss(String, String, String)}).
   */
  public void recordMiss() {

    misses.increment();

  }

}
