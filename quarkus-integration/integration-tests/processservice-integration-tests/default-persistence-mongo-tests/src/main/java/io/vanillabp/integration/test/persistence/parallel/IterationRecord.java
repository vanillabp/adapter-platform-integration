package io.vanillabp.integration.test.persistence.parallel;

import org.bson.codecs.pojo.annotations.BsonId;

/**
 * A document of its own collection. Each iteration of the parallel multi-instance writes its
 * result into a record of its own, so the records of the iterations never collide. The
 * aggregate only holds the ids.
 */
public class IterationRecord {

  @BsonId
  private String id;

  private String result;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getResult() {
    return result;
  }

  public void setResult(
      final String result) {
    this.result = result;
  }

}
