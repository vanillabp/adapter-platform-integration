package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow service which inherits its declaration.
 */
@Getter
@Setter
public class InheritedAggregate {

  private String id;

  // the test reads this, no BPMN model does - so it stays out of the BPMS

  @NoSyncWithBPMS

  private String servedBy;

}
