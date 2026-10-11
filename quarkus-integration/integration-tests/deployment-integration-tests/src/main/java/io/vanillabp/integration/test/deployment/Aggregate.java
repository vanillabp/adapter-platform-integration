package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * A workflow aggregate whose ID is not a string. The outbox stores the ID as a string and
 * converts it back before it dispatches phase two. The outbox-recovery ordering test needs
 * this.
 */
public class Aggregate {

  private Long id;

  // the test reads this field, but no BPMN model does, so it stays out of the BPMS
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
