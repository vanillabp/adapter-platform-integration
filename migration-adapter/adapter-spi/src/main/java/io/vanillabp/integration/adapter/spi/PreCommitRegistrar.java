package io.vanillabp.integration.adapter.spi;

/**
 * Lets a BPMS adapter run a phase-one check right before the transaction of the workflow
 * aggregate commits.
 * <p>
 * A phase-one check must not move the process forward, but it may ASK. For example, it may
 * ask whether the task still exists, or whether the model declares a message. The answer
 * can be out of date by the time phase two is sent. The later the check runs, the smaller
 * that gap. So an adapter hands its check over here, instead of running it when the
 * application calls. If the check throws, the commit is aborted. That way the application
 * learns about an outdated operation at the place where it made the call.
 * <p>
 * The platform integrations look up the transaction runner of the workflow aggregate and
 * ask it. This may be a runner the APPLICATION contributed. So a unit of work owned by the
 * application is hooked into, not bypassed. A runner which does not implement the hook runs
 * the check at once. Every adapter behaved like this before this interface existed.
 */
@FunctionalInterface
public interface PreCommitRegistrar {

  /**
   * Runs the given check right before the transaction of the given workflow aggregate
   * commits, or immediately where that cannot be arranged.
   *
   * @param workflowAggregateClass The workflow aggregate whose transaction is meant. It
   *          decides which runner is asked
   * @param check The check to run; throwing aborts the commit
   */
  void beforeCommit(
      Class<?> workflowAggregateClass,
      Runnable check);

}
