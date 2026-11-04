package io.vanillabp.integration.adapter.spi;

import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;

/**
 * Where an adapter reports that it found a workflow suspended in its BPMS. The core implements
 * it and tells every extension which matches the adapter (see
 * {@link io.vanillabp.integration.extension.spi.ExtensionWiringService#workflowFoundSuspended}).
 * <p>
 * An adapter calls it in two places, and only where it really found a suspension:
 * <ul>
 * <li>in phase one, just before it refuses the application's call with a
 * <code>WorkflowSuspendedException</code>, with {@link SuspendedWorkflowFound.Phase#CALL},</li>
 * <li>in phase two, just before it lets the outbox entry wait, with
 * {@link SuspendedWorkflowFound.Phase#WAITING}, on every attempt.</li>
 * </ul>
 * It calls it before it throws, not in a <code>finally</code>. A signal or a message sent to a
 * suspended workflow is not reported, because no adapter refuses those calls.
 * <p>
 * {@link #found(SuspendedWorkflowFound)} never throws and returns at once. Every exception of an
 * extension is caught and logged in the core. So the adapter throws afterwards exactly what it
 * would have thrown without the report (see decision 134 in the repository's
 * DECISIONS.md).
 */
@FunctionalInterface
public interface SuspendedWorkflowReporter {

  /**
   * The reporter of an adapter which was built without one. It drops every report.
   */
  SuspendedWorkflowReporter NOBODY_LISTENS = found -> {
    // nobody to tell
  };

  /**
   * Reports a workflow which the adapter found suspended.
   *
   * @param found What the adapter found
   */
  void found(
      SuspendedWorkflowFound found);

}
