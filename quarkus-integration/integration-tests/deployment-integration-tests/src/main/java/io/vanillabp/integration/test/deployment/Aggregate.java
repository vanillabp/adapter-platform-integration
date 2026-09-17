package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * A workflow aggregate having a non-string ID: the outbox serializes the ID as a
 * string and converts it back before dispatching phase two (relevant for the
 * outbox-recovery ordering test).
 */
public class Aggregate {

  private Long id;

  // the test reads this, no BPMN model does - so it stays out of the BPMS
  @NoSyncWithBPMS
  private String content;

  public Long getId() {
    return id;
  }

  public void setId(
      final Long id) {
    this.id = id;
  }

  public String getContent() {
    return content;
  }

  public void setContent(
      final String content) {
    this.content = content;
  }

}
