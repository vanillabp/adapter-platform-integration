package io.vanillabp.integration.test.transaction;

/**
 * The workflow aggregate of the application-owned-transaction test: stored by
 * the application in a system Spring knows nothing about, so nothing here is a JPA entity
 * or a MongoDB document.
 */
public class AppTxAggregate implements AppTxStored {

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
