package io.vanillabp.integration.test.delivery;

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

  private int invocations;

}
