package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The aggregate of the acceptance test of workflows which the BPMS starts on its own. Its
 * ID is a String, so the trigger time of a timer can be its identity.
 */
public class StartAggregate {

  private String id;

  private String region;

  private int amount;

  /**
   * Set by the <code>&#64;WorkflowStartedByBpms</code> method. So a test can tell whether
   * the application took part, or whether VanillaBP built the aggregate alone.
   */
  // the test reads this field, but no BPMN model does, so it stays out of the BPMS
  @NoSyncWithBPMS
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
