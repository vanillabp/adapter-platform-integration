package io.vanillabp.integration.test.persistence.parallel;

import java.util.ArrayList;
import java.util.List;

import org.bson.codecs.pojo.annotations.BsonId;

/**
 * The aggregate of the parallel multi-instance test which a MongoDB Panache repository
 * stores ({@link ParallelRepositoryAggregateRepository}).
 * <p>
 * MongoDB Panache has no reference to a document of another collection. The codec writes
 * an object field as a sub-document. So an aggregate points to a document elsewhere by its
 * id, as {@link #getIterationRecordIds()} does.
 */
public class ParallelRepositoryAggregate {

  @BsonId
  private String id;

  private String content;

  private List<String> iterationRecordIds = new ArrayList<>();

  private Details details;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getContent() {
    return content;
  }

  public void setContent(
      final String content) {
    this.content = content;
  }

  public List<String> getIterationRecordIds() {
    return iterationRecordIds;
  }

  public void setIterationRecordIds(
      final List<String> iterationRecordIds) {
    this.iterationRecordIds = iterationRecordIds;
  }

  public Details getDetails() {
    return details;
  }

  public void setDetails(
      final Details details) {
    this.details = details;
  }

}
