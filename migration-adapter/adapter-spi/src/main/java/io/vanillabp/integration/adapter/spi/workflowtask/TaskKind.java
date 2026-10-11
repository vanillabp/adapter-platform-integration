package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Which kind of task a delivery was about, as the adapter reports it in
 * {@link TaskInvocationContext#getTaskKind()}.
 * <p>
 * The platform tells these two kinds apart anyway. A task is completed through
 * {@code ProcessService#completeTask}, a user task through
 * {@code ProcessService#completeUserTask}.
 * {@link io.vanillabp.integration.spi.Election#HOLDS_THE_TASK} asks a BPMS about a task,
 * {@link io.vanillabp.integration.spi.Election#HOLDS_THE_USER_TASK} about a user task. The
 * ids of each kind have a namespace of their own. A Camunda 7 task id is not an execution
 * id, and a Camunda 8 user-task key is not a job key. So the command for the other kind
 * finds nothing under such an id.
 * <p>
 * The kind is written into the delivery record
 * ({@link io.vanillabp.integration.spi.TaskDelivery#taskKind()}). So VanillaBP can tell the
 * caller which method to use, instead of listing guesses. The id a caller names was recorded
 * as one of these kinds, and the method which asks for that kind of key is the right one.
 */
public enum TaskKind {

  /**
   * A task the BPMS hands to the application to work on: a Camunda 8 job, a Camunda 7
   * external task or job, or whatever the BPMS calls the work it pushes. It is completed and
   * cancelled through {@code completeTask} and {@code cancelTask}.
   */
  TASK,

  /**
   * A user task the BPMS manages. A person works on it through a task list or a form. It is
   * completed and cancelled through {@code completeUserTask} and {@code cancelUserTask}.
   */
  USER_TASK

}
