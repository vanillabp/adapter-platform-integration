package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.TaskEvent;

/**
 * The aggregate of the task-processing acceptance test.
 */
public class TaskAggregate {

  private String id;

  private String status;

  private String taskId;

  private TaskEvent.Event event;

  private Object element;

  private int index;

  private int total;

  private String requestScopedProbe;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(
      final String status) {
    this.status = status;
  }

  public String getTaskId() {
    return taskId;
  }

  public void setTaskId(
      final String taskId) {
    this.taskId = taskId;
  }

  public TaskEvent.Event getEvent() {
    return event;
  }

  public void setEvent(
      final TaskEvent.Event event) {
    this.event = event;
  }

  public Object getElement() {
    return element;
  }

  public void setElement(
      final Object element) {
    this.element = element;
  }

  public int getIndex() {
    return index;
  }

  public void setIndex(
      final int index) {
    this.index = index;
  }

  public int getTotal() {
    return total;
  }

  public void setTotal(
      final int total) {
    this.total = total;
  }

  public String getRequestScopedProbe() {
    return requestScopedProbe;
  }

  public void setRequestScopedProbe(
      final String requestScopedProbe) {
    this.requestScopedProbe = requestScopedProbe;
  }

}
