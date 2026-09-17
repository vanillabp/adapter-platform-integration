package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;
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
  // the test reads this, no BPMN model does - so it stays out of the BPMS
  @NoSyncWithBPMS
  private String servedBy;

}
