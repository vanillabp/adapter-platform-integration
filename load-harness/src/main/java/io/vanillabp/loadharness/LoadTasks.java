package io.vanillabp.loadharness;

/**
 * What the <code>&#64;WorkflowTask</code> methods of the load process do. The workflow
 * service is a bean of the platform, so each platform writes its own small class with the
 * annotations, and that class hands every call on to here. The methods do as little as
 * possible, because the run measures VanillaBP and not a handler.
 */
public final class LoadTasks {

  private LoadTasks() {
  }

  /**
   * A service task: counts that it ran.
   *
   * @param aggregate The workflow aggregate
   */
  public static void step(
      final LoadAggregate aggregate) {

    aggregate.setStepsDone(aggregate.getStepsDone() + 1);

  }

  /**
   * The task which the application completes later: notes the task id, so the driver can
   * complete it.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The id of the task, from the <code>&#64;TaskId</code> parameter
   */
  public static void waitForTheApplication(
      final LoadAggregate aggregate,
      final String taskId) {

    aggregate.setWaitTaskId(taskId);

  }

}
