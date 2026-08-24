package io.vanillabp.integration.test.deployment;

/**
 * The aggregate of the multi-instance resolver test.
 */
public class ResolverAggregate {

  private String id;

  private String resolved;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getResolved() {
    return resolved;
  }

  public void setResolved(
      final String resolved) {
    this.resolved = resolved;
  }

}
