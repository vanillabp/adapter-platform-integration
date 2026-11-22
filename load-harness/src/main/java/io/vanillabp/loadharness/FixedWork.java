package io.vanillabp.loadharness;

import java.time.Duration;

/**
 * One amount of work: how many workflows, how many service tasks each, and how the harness
 * plays the BPMS. A run is always a fixed amount of work and never a fixed time. A run which
 * stops after a minute compares with nothing, and it cannot check that the numbers which
 * have to be zero are zero.
 *
 * @param workflows How many workflows run to completion
 * @param serviceTasks How many service tasks each workflow runs (see {@link LoadProcess})
 * @param slots How many pieces of work the harness hands to the application at the same
 *          time. Four is what the Camunda 8 adapter uses by default. The queue in front of
 *          the slots is bounded as well, because an unbounded queue would hide an empty
 *          connection pool behind itself
 * @param phaseTwoDelay How long the BPMS takes to answer one phase-two call. A remote BPMS
 *          takes time, and that time holds a connection and an outbox entry open. Zero asks
 *          what VanillaBP costs. A few milliseconds ask what a slow neighbour makes of it
 */
public record FixedWork(
                        int workflows,
                        int serviceTasks,
                        int slots,
                        Duration phaseTwoDelay) {

  /**
   * The number of slots when nothing else is said, the same as the Camunda 8 adapter.
   */
  public static final int DEFAULT_SLOTS = 4;

  /**
   * Checks the values.
   *
   * @param workflows How many workflows run to completion
   * @param serviceTasks How many service tasks each workflow runs
   * @param slots How many pieces of work run at the same time
   * @param phaseTwoDelay How long the BPMS takes to answer one phase-two call
   */
  public FixedWork {

    if (workflows < 1) {
      throw new IllegalArgumentException("A fixed work needs at least one workflow, but got %d".formatted(workflows));
    }
    if (serviceTasks < 1) {
      throw new IllegalArgumentException(
          "A fixed work needs at least one service task, but got %d".formatted(serviceTasks));
    }
    if (slots < 1) {
      throw new IllegalArgumentException("A fixed work needs at least one slot, but got %d".formatted(slots));
    }
    if ((phaseTwoDelay == null) || phaseTwoDelay.isNegative()) {
      throw new IllegalArgumentException("The delay of a phase-two call must be zero or more, but was %s"
          .formatted(phaseTwoDelay));
    }

  }

  /**
   * The same work with a different number of workflows.
   *
   * @param otherWorkflows How many workflows the other work runs
   * @return The other work
   */
  public FixedWork withWorkflows(
      final int otherWorkflows) {

    return new FixedWork(otherWorkflows, serviceTasks, slots, phaseTwoDelay);

  }

}
