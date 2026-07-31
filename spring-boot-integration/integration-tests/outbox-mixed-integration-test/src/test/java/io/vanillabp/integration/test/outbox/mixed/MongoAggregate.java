package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * A MongoDB workflow aggregate: its outbox entries have to land in the MongoDB
 * outbox, riding the MongoDB transaction - even though JPA aggregates exist in the
 * same application (mixed persistence).
 */
@Document(collection = "mixed-mongo-aggregate")
@Getter
@Setter
public class MongoAggregate {

  @Id
  private String id;

  private String content;

}
