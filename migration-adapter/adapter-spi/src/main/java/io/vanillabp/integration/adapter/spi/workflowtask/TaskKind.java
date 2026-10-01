package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * Which kind of task a delivery was about, as the adapter reports it in
 * {@link TaskInvocationContext#getTaskKind()}.
 * <p>
 * The two values are the two the platform tells apart anyway: a task is completed through
 * {@code ProcessService#completeTask} and a user task through
 * {@code ProcessService#completeUserTask}, and
 * {@link io.vanillabp.integration.spi.Election#HOLDS_THE_TASK} respectively
 * {@link io.vanillabp.integration.spi.Election#HOLDS_THE_USER_TASK} asks a BPMS about one of
 * them. The ids live in namespaces of their own - a Camunda 7 task id is not an execution
 * id, a Camunda 8 user-task key is not a job key - so the command of the other kind finds
 * nothing under such an id.
 * <p>
 * The kind travels into the delivery record
 * ({@link io.vanillabp.integration.spi.TaskDelivery#taskKind()}), which is what lets
 * VanillaBP say so instead of listing guesses: the id a caller names was recorded as one of
 * these, and the method which asks for that kind of key is the method to use.
 */
public enum TaskKind {

  /**
   * A task the BPMS hands to the application to work off: a Camunda 8 job, a Camunda 7
   * external task or job, whatever the BPMS calls the work it pushes. Completed and
   * cancelled through {@code completeTask} and {@code cancelTask}.
   */
  TASK,

  /**
   * A user task the BPMS manages, which a person works off through a task list or a form.
   * Completed and cancelled through {@code completeUserTask} and {@code cancelUserTask}.
   */
  USER_TASK

}
