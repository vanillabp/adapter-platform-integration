package io.vanillabp.integration.test.persistence.parallel;

import java.util.ArrayList;
import java.util.List;

import org.bson.codecs.pojo.annotations.BsonId;

import io.quarkus.mongodb.panache.PanacheMongoEntityBase;

/**
 * The aggregate of the parallel multi-instance test which stores itself, in the active
 * record idiom of MongoDB Panache. It points to the records of its iterations by their ids.
 */
public class ParallelActiveRecordAggregate extends PanacheMongoEntityBase {

  @BsonId
  public String id;

  public String content;

  public List<String> iterationRecordIds = new ArrayList<>();

  public Details details;

}
