package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;

@Document(collection = "test2_entity")
@Getter
@Setter
public class Entity2 {

  @Id
  private String entityId;

  @Column
  private String entityValue;

}
