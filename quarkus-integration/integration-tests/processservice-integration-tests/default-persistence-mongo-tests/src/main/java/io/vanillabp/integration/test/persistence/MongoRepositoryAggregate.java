package io.vanillabp.integration.test.persistence;

import org.bson.codecs.pojo.annotations.BsonId;

/**
 * The aggregate of the MongoDB Panache repository idiom, managed by
 * {@link MongoRepositoryAggregateRepository}.
 */
public class MongoRepositoryAggregate {

  @BsonId
  private String id;

  private String status;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(
      final String status) {
    this.status = status;
  }

}
