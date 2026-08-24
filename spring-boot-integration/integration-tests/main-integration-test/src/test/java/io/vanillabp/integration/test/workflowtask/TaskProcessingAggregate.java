package io.vanillabp.integration.test.workflowtask;

import io.vanillabp.spi.service.TaskEvent;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the task-processing acceptance test (see
 * {@code WorkflowTaskProcessingTest}). Lives in this package for the same
 * classpath-scan reason as {@code OverriddenAggregate}: @WorkflowService classes
 * leak into every test of this Maven module, and this package sorts last.
 */
@Getter
@Setter
public class TaskProcessingAggregate {

  private String id;

  private String status;

  /**
   * Excluded from what the BPMS sees - and by deriving the class' mode
   * (opt-out) it is the ONLY excluded attribute.
   */
  @io.vanillabp.spi.service.NoSyncWithBPMS
  private String taskId;

  private TaskEvent.Event event;

  private Object element;

  private int index;

  private int total;

}
