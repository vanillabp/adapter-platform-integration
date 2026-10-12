package io.vanillabp.integration.test.activation;

/**
 * The workflow aggregate of the activation-identity test. All siblings of a
 * multi-instance call activity share it. That is why their correlations collide. So it
 * carries nothing which could tell them apart.
 */
public class ActivationAggregate {

  private String id;

  private int correlations;

  public String getId() {

    return id;

  }

  public void setId(
      final String id) {

    this.id = id;

  }

  public int getCorrelations() {

    return correlations;

  }

  public void setCorrelations(
      final int correlations) {

    this.correlations = correlations;

  }

}
