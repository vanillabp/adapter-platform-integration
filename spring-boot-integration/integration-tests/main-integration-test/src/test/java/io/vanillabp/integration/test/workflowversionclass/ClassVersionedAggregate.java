package io.vanillabp.integration.test.workflowversionclass;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the class-level version acceptance test. Lives in its own package
 * for the same classpath-scan reason as the other test aggregates:
 * {@code @WorkflowService} classes are found by scanning, so every test of this Maven
 * module sees the two classes next to it as well.
 */
@Getter
@Setter
public class ClassVersionedAggregate {

  private String id;

  /**
   * Which class served the task - the version of the deployed process decides it,
   * although no method names a version.
   */
  private String servedBy;

}
