package io.vanillabp.integration.test.utils.impl.jpa;

import jakarta.persistence.Id;
import lombok.Setter;

/**
 * Both places carry the annotation, and the order between them is the point: the field
 * wins. The class is not a JPA entity. Hibernate refuses to map an entity whose access type
 * is ambiguous. But reading the ID name is pure reflection and needs no mapping.
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
