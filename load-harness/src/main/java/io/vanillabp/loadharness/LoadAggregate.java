package io.vanillabp.loadharness;

/**
 * The workflow aggregate of the load process. It holds only what the harness needs to go
 * on: its id, how many service tasks ran, and the id of the task which the application
 * completes later.
 */
public class LoadAggregate {

  private String id;

  private int stepsDone;

  private String waitTaskId;

  /**
   * An empty aggregate, for a persistence which builds one.
   */
  public LoadAggregate() {
  }

  /**
   * A new aggregate with a given id.
   *
   * @param id The id of the workflow aggregate
   */
  public LoadAggregate(
      final String id) {

    this.id = id;

  }

  /**
   * The id of the workflow aggregate.
   *
   * @return The id
   */
  public String getId() {

    return id;

  }

  /**
   * Sets the id of the workflow aggregate.
   *
   * @param id The id
   */
  public void setId(
      final String id) {

    this.id = id;

  }

  /**
   * How many service tasks ran for this workflow.
   *
   * @return The number of service tasks which ran
   */
  public int getStepsDone() {

    return stepsDone;

  }

  /**
   * Sets how many service tasks ran for this workflow.
   *
   * @param stepsDone The number of service tasks which ran
   */
  public void setStepsDone(
      final int stepsDone) {

    this.stepsDone = stepsDone;

  }

  /**
   * The id of the task which the application completes later, once it was delivered.
   *
   * @return The task id, or <code>null</code> before the delivery
   */
  public String getWaitTaskId() {

    return waitTaskId;

  }

  /**
   * Sets the id of the task which the application completes later.
   *
   * @param waitTaskId The task id
   */
  public void setWaitTaskId(
      final String waitTaskId) {

    this.waitTaskId = waitTaskId;

  }

  LoadAggregate copy() {

    final var copy = new LoadAggregate(id);
    copy.stepsDone = stepsDone;
    copy.waitTaskId = waitTaskId;
    return copy;

  }

}
