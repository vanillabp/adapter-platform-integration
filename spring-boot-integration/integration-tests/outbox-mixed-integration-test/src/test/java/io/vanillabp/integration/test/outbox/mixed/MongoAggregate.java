package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * A MongoDB workflow aggregate. Its outbox entries must land in the MongoDB outbox, inside
 * the MongoDB transaction. This holds even though the same application also has JPA
 * aggregates (mixed persistence).
 */
@Document(collection = "mixed-mongo-aggregate")
@Getter
@Setter
public class MongoAggregate {

  @Id
  private String id;

  private String content;

}
