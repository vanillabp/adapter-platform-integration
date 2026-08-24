package io.vanillabp.integration.test.delivery;

/**
 * The aggregate of the inbound-idempotency test. It counts how often a
 * handler ran on it - the business code a repeated delivery must not run again.
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
