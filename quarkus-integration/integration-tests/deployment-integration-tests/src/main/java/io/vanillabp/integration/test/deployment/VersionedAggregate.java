package io.vanillabp.integration.test.deployment;

/**
 * The aggregate of the process-version acceptance test.
 */
public class VersionedAggregate {

  private String id;

  /**
   * Which method served the task - the version of the deployed process decides it.
   */
  private String servedBy;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getServedBy() {
    return servedBy;
  }

  public void setServedBy(
      final String servedBy) {
    this.servedBy = servedBy;
  }

}
