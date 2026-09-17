package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the transaction-contract acceptance test.
 */
@Getter
@Setter
public class TransactionAggregate {

  private String id;

  // the test reads this, no BPMN model does - so it stays out of the BPMS

  @NoSyncWithBPMS

  private String status;

}
