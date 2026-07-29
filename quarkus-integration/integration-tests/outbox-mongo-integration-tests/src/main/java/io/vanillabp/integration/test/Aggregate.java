package io.vanillabp.integration.test;

/**
 * A workflow aggregate having a non-string ID: the outbox has to serialize the ID as
 * a string and convert it back before dispatching phase two.
 */
public class Aggregate {

  private Long id;

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
