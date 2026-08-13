package io.vanillabp.integration.test.workflowversion;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the process-version acceptance test (story 48). Lives in its own
 * package for the same classpath-scan reason as the other test aggregates:
 * {@code @WorkflowService} classes are found by scanning, so every test of this Maven
 * module sees this one as well.
 */
@Getter
@Setter
public class VersionedAggregate {

  private String id;

  /**
   * Which method served the task - the version decides it.
   */
  private String servedBy;

}
