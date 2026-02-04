package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

@Document(collection = "test_entity")
@Getter
@Setter
public class Entity {

  @Id
  private String id;

  @Column
  private String entityValue;

}
