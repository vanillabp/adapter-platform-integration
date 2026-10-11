package io.vanillabp.integration.adapter.spi.workflowstart;

/**
 * Where an adapter says which workflow its BPMS created. The adapter gets it with phase two of
 * a start (see
 * {@code io.vanillabp.integration.adapter.spi.PhaseTwoRequest#reportStartedWorkflow(String)}).
 *
 * <strong>Why it is a sink and not a return value</strong>
 *
 * Phase two of an operation returns nothing, and that is right for the other operations. They
 * act, and what the BPMS says about them is up to the BPMS. A start is the one operation which
 * creates a NAME, and only the adapter which created the instance ever sees it. With a sink,
 * this one operation can report the name, and every other handler stays a method which returns
 * nothing. An adapter which reports nothing keeps compiling and behaves as before.
 *
 * <strong>Which id is meant</strong>
 *
 * The id of the workflow this aggregate IS. On a BPMS with call activities, that is the
 * top-most parent instance. The instances created below it belong to tasks and travel with those
 * tasks. So nothing here has to walk up a chain of parents.
 */
public interface WorkflowStartReport {

  /**
   * A sink which drops what it is told, used where nothing listens.
   */
  WorkflowStartReport NOBODY_LISTENS = workflowId -> {
  };

  /**
   * Says which workflow of the BPMS was created. It is called at most once per phase two, from
   * the thread which dispatches it. A <code>null</code> is the same as not calling it at all.
   *
   * @param workflowId The BPMS' own id of the started workflow
   */
  void startedWorkflow(
      String workflowId);

  /**
   * Says which workflow of the BPMS was created, and on which version of its process definition.
   * The same rules as for {@link #startedWorkflow(String)}: at most once per phase two, and a
   * <code>null</code> id is the same as not calling it at all.
   * <p>
   * The default drops the version and passes the id on. So a sink written before the version
   * existed keeps working. And this interface stays a functional one, so a test of an adapter
   * can still pass a lambda.
   *
   * @param workflowId The BPMS' own id of the started workflow
   * @param processVersion The version of the process definition the workflow was started on, as
   *          the BPMS counts it, or <code>null</code> if the BPMS does not say
   */
  default void startedWorkflow(
      final String workflowId,
      final String processVersion) {

    startedWorkflow(workflowId);

  }

}
