package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;
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
  // the test reads this, no BPMN model does - so it stays out of the BPMS
  @NoSyncWithBPMS
  private String servedBy;

}
