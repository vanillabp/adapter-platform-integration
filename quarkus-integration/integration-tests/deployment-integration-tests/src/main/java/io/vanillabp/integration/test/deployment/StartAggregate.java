package io.vanillabp.integration.test.deployment;

/**
 * The aggregate of the acceptance test of workflows the BPMS starts on its own. Its
 * ID is a String, so a timer's trigger time can be its identity.
 */
public class StartAggregate {

  private String id;

  private String region;

  private int amount;

  /**
   * Set by the <code>&#64;WorkflowStartedByBpms</code> method, so a test can tell
   * whether the application had its say or VanillaBP built the aggregate alone.
   */
  private String startedBy;

  public String getId() {

    return id;

  }

  public void setId(
      final String id) {

    this.id = id;

  }

  public String getRegion() {

    return region;

  }

  public void setRegion(
      final String region) {

    this.region = region;

  }

  public int getAmount() {

    return amount;

  }

  public void setAmount(
      final int amount) {

    this.amount = amount;

  }

  public String getStartedBy() {

    return startedBy;

  }

  public void setStartedBy(
      final String startedBy) {

    this.startedBy = startedBy;

  }

}
