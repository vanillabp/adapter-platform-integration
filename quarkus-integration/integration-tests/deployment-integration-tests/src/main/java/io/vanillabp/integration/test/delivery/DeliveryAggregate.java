package io.vanillabp.integration.test.delivery;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the inbound-idempotency test. It counts how often a handler ran on
 * it. A repeated delivery must not run this business code again.
 */
@Getter
@Setter
public class DeliveryAggregate {

  private String id;

  private String status;

  // the test reads this field, but no BPMN model does, so it stays out of the BPMS

  @NoSyncWithBPMS

  private int invocations;

  /**
   * The tasks whose cancellation the application was told about, in the order they
   * arrived and separated by a comma. A test reads it to see which cancellations really
   * reached a <code>&#64;WorkflowTask</code> method.
   */
  @NoSyncWithBPMS

  private String canceledTasks;

}
