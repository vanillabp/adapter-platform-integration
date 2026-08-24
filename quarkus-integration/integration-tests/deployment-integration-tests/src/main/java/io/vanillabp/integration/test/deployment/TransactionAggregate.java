package io.vanillabp.integration.test.deployment;

/**
 * The aggregate of the transaction-contract acceptance test.
 */
public class TransactionAggregate {

  private String id;

  private String status;

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

}
