package io.vanillabp.integration.test.outbox;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

@Document(collection = "outbox-test-aggregate")
@Getter
@Setter
public class Aggregate {

  @Id
  private String id;

  private String content;

}
