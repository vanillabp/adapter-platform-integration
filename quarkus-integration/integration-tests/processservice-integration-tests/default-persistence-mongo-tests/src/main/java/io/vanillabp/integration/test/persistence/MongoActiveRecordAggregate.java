package io.vanillabp.integration.test.persistence;

import org.bson.codecs.pojo.annotations.BsonId;

import io.quarkus.mongodb.panache.PanacheMongoEntityBase;

/**
 * The aggregate of the MongoDB Panache active record idiom. There is no repository
 * anywhere. The entity itself knows how to store itself.
 */
public class MongoActiveRecordAggregate extends PanacheMongoEntityBase {

  @BsonId
  public String id;

  public String status;

}
