package io.vanillabp.integration.test.utils.impl.jpa;

import jakarta.persistence.Id;
import lombok.Setter;

/**
 * Both places carry the annotation, which is what the order is about: the field wins.
 * The class is not a JPA entity, because Hibernate refuses to map one whose access type
 * is ambiguous, while reading the ID name is pure reflection and needs no mapping.
 */
@Setter
public class ClassWithIdOnFieldAndGetter {

  @Id
  private Long entityId;

  @Id
  public Long getAnotherId() {

    return entityId;

  }

}
