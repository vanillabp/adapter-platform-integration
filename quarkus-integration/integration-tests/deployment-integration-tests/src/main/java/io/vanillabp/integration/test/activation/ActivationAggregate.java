package io.vanillabp.integration.test.activation;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The workflow aggregate of the activation-identity test. All siblings of a
 * multi-instance call activity share it - that is what makes their correlations collide
 * - so it carries nothing which could tell them apart.
 */
public class ActivationAggregate {

  private String id;

  // the test reads this, no BPMN model does - so it stays out of the BPMS

  @NoSyncWithBPMS

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
