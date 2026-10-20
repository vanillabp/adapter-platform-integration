package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * The document behind the lazy reference of the aggregate. It has a collection of its own.
 * So the test can count the queries on it, and a query there means that the lazy reference
 * was resolved.
 */
@Document(collection = "parallel-test-lazy-record")
@Getter
@Setter
public class LazyRecord {

  @Id
  private String id;

  private String result;

}
