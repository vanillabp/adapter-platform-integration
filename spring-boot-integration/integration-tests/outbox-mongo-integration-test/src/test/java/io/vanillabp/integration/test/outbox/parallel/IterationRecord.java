package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * A document of its own collection which a workflow aggregate references. Each iteration
 * of the parallel multi-instance writes the result into a record of its own. So the
 * records of the iterations never collide, and the aggregate only points to them.
 */
@Document(collection = "parallel-test-iteration-record")
@Getter
@Setter
public class IterationRecord {

  @Id
  private String id;

  private String result;

}
