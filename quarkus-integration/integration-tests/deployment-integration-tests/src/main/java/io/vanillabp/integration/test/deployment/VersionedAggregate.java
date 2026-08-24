package io.vanillabp.integration.test.deployment;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the process-version acceptance test.
 */
@Getter
@Setter
public class VersionedAggregate {

  private String id;

  /**
   * Which method served the task - the version of the deployed process decides it.
   */
  private String servedBy;

}
