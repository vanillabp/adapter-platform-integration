package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.Getter;
import lombok.Setter;

@Document(collection = "test2_entity")
@Getter
@Setter
public class EntityWithoutRepository {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer entityId;

  @Column
  private String entityValue;

}
