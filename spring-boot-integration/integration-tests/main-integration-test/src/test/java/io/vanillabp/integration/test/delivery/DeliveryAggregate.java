package io.vanillabp.integration.test.delivery;

import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the inbound-idempotency test. It lives in its own
 * package together with its workflow service, so the scanning of the test application
 * picks up exactly these two.
 */
@Getter
@Setter
public class DeliveryAggregate {

  private String id;

  private String status;

  private int invocations;

}
