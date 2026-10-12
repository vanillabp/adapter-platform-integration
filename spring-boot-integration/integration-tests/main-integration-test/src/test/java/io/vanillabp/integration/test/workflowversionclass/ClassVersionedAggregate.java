package io.vanillabp.integration.test.workflowversionclass;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the acceptance test for versions at class level. It lives in its own
 * package for the same reason as the other test aggregates. A scan finds the
 * {@code @WorkflowService} classes, so every test of this Maven module also sees the two
 * classes next to it.
 */
@Getter
@Setter
public class ClassVersionedAggregate {

  private String id;

  /**
   * Which class served the task. The version of the deployed process decides it,
   * although no method names a version.
   */
  private String servedBy;

}
