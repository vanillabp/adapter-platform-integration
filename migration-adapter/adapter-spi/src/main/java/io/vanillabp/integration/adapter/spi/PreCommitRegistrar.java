package io.vanillabp.integration.adapter.spi;

/**
 * Lets a BPMS adapter run a phase-one check right before the transaction of the workflow
 * aggregate commits.
 * <p>
 * A phase-one check must not advance the process, but it may ASK - whether the task still
 * exists, whether the model declares a message - and the answer can go stale between the
 * question and the phase-two dispatch. The later the check runs, the smaller that window,
 * so an adapter hands its check here instead of running it when the application calls.
 * A check which throws aborts the commit, which is what makes the application learn about
 * a stale operation where it made the call.
 * <p>
 * The platform integrations implement this by resolving the transaction runner of the
 * workflow aggregate (which may be a runner the APPLICATION contributed) and
 * asking it, so an application-owned unit of work is hooked into rather than bypassed. A
 * runner which does not implement the hook runs the check immediately - the behaviour of
 * every adapter before this existed.
 */
@FunctionalInterface
public interface PreCommitRegistrar {

  /**
   * Runs the given check right before the transaction of the given workflow aggregate
   * commits, or immediately where that cannot be arranged.
   *
   * @param workflowAggregateClass The workflow aggregate whose transaction is meant - it
   *          decides which runner is asked
   * @param check The check to run; throwing aborts the commit
   */
  void beforeCommit(
      Class<?> workflowAggregateClass,
      Runnable check);

}
