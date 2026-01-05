package io.vanillabp.integration.test.utils.impl.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "test2_entity")
@Getter
@Setter
public class EntityWithoutRepository {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer entityId;

  @Column
  private String entityValue;

}
