package io.vanillabp.integration.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * A workflow aggregate whose ID is not a string. The outbox has to write the ID as a
 * string, and convert it back before it dispatches phase two.
 */
public class Aggregate {

  private Long id;

  // the test reads this, but no BPMN model does. So it stays out of the BPMS

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
