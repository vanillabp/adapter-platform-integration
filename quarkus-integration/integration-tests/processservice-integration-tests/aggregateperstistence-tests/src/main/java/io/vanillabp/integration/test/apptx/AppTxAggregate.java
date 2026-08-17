package io.vanillabp.integration.test.apptx;

/**
 * A workflow aggregate stored in a system neither JTA nor Panache knows: no entity, no
 * document, just an object the application persists itself.
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
