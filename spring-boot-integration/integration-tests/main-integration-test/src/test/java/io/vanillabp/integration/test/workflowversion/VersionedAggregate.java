package io.vanillabp.integration.test.workflowversion;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the acceptance test for process versions. It lives in its own package
 * for the same reason as the other test aggregates. A scan finds the
 * {@code @WorkflowService} classes, so every test of this Maven module sees this one as
 * well.
 */
@Getter
@Setter
public class VersionedAggregate {

  private String id;

  /**
   * Which method served the task. The version decides it.
   */
  private String servedBy;

}
