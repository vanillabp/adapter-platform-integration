package io.vanillabp.integration.test.deployment;

import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the class-level version acceptance test.
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
