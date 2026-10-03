package io.vanillabp.integration.adapter.spi.workflowstart;

/**
 * Where an adapter says which workflow its BPMS created, handed to it with the phase two of a
 * start (see
 * {@code io.vanillabp.integration.adapter.spi.PhaseTwoRequest#reportStartedWorkflow(String)}).
 *
 * <strong>Why it is a sink and not a return value</strong>
 *
 * Phase two of an operation returns nothing, and that is right for the other operations: they
 * act, and what the BPMS says about them is the BPMS' business. A start is the one operation
 * which brings a NAME into the world, and nobody but the adapter which created the instance ever
 * sees it. A sink lets the one operation which has something to report report it while every
 * other handler stays a method which returns nothing, and an adapter which reports nothing keeps
 * compiling and behaving as before.
 *
 * <strong>Which id is meant</strong>
 *
 * The id of the workflow this aggregate IS, which on a BPMS with call activities is the
 * super-parent instance. The instances created underneath belong to tasks and travel with those
 * tasks, so nothing here has to walk a parent chain.
 */
public interface WorkflowStartReport {

  /**
   * A sink which drops what it is told, used where nothing listens.
   */
  WorkflowStartReport NOBODY_LISTENS = workflowId -> {
  };

  /**
   * Says which workflow of the BPMS was created. Called at most once per phase two, from the
   * thread dispatching it, and a <code>null</code> is the same as not calling it at all.
   *
   * @param workflowId The BPMS' own id of the started workflow
   */
  void startedWorkflow(
      String workflowId);

}
