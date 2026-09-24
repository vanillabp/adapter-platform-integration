package io.vanillabp.integration.test.utils.impl.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Setter;

/**
 * An entity which carries the ID annotation at the getter, which JPA allows and
 * applications use. The getter is written by hand because Lombok cannot be told to
 * annotate the one it generates.
 */
@Entity
@Table(name = "test4_entity")
@Setter
public class EntityWithIdOnGetter {

  private Long entityId;

  @Column
  private String entityValue;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long getEntityId() {

    return entityId;

  }

  public String getEntityValue() {

    return entityValue;

  }

}
