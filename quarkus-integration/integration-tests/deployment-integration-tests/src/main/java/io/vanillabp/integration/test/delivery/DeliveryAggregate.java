package io.vanillabp.integration.test.delivery;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the inbound-idempotency test. It counts how often a
 * handler ran on it - the business code a repeated delivery must not run again.
 */
@Getter
@Setter
public class DeliveryAggregate {

  private String id;

  private String status;

  // the test reads this, no BPMN model does - so it stays out of the BPMS

  @NoSyncWithBPMS

  private int invocations;

}
