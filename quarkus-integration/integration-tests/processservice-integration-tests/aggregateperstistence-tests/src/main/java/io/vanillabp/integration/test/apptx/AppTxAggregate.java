package io.vanillabp.integration.test.apptx;

/**
 * A workflow aggregate which neither JTA (the Java Transaction API) nor Panache knows. It is
 * no entity and no document, just an object which the application stores itself.
 */
public class AppTxAggregate implements AppTxStored {

  private String id;

  private String status;

  private int invocations;

  @Override
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
