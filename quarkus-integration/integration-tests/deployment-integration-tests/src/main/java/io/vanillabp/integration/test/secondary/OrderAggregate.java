package io.vanillabp.integration.test.secondary;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the called-process test. One aggregate carries both BPMN processes of
 * its workflow service. So a record of the called process says something about the
 * workflow which the primary process addresses.
 */
@Getter
@Setter
public class OrderAggregate {

  private String id;

  // the test reads this field, but no BPMN model does, so it stays out of the BPMS

  @NoSyncWithBPMS

  private String status;

}
