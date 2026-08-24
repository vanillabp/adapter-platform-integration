package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.TaskEvent;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the task-processing acceptance test.
 */
@Getter
@Setter
public class TaskAggregate {

  private String id;

  private String status;

  private String taskId;

  private TaskEvent.Event event;

  private Object element;

  private int index;

  private int total;

  private String requestScopedProbe;

}
