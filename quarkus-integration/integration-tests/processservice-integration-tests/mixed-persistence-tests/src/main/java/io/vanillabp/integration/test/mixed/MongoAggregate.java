package io.vanillabp.integration.test.mixed;

import org.bson.codecs.pojo.annotations.BsonId;

import io.quarkus.mongodb.panache.PanacheMongoEntityBase;

/**
 * The aggregate stored in MongoDB, in the same workflow module as {@link JpaAggregate}.
 * Both are active records, so neither of them is configured anywhere - which is what
 * leaves VanillaBP with the question this application answers.
 */
public class MongoAggregate extends PanacheMongoEntityBase {

  @BsonId
  public String id;

  public String status;

}
