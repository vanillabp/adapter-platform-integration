package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Document(collection = "test3_entity")
public abstract class BaseEntity {

  @Id
  private String id;

}
