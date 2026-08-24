package io.vanillabp.integration.test.delivery;

/**
 * The workflow aggregate of the inbound-idempotency test. It lives in its own
 * package together with its workflow service, so the scanning of the test application
 * picks up exactly these two.
 */
public class DeliveryAggregate {

  private String id;

  private String status;

  private int invocations;

  public String getId() {

    return id;

  }

  public void setId(
      final String id) {

    this.id = id;

  }

  public String getStatus() {

    return status;

  }

  public void setStatus(
      final String status) {

    this.status = status;

  }

  public int getInvocations() {

    return invocations;

  }

  public void setInvocations(
      final int invocations) {

    this.invocations = invocations;

  }

}
