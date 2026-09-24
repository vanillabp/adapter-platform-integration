package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Setter;

/**
 * The MongoDB twin of the JPA entity of the same name: the ID annotation sits at the
 * getter, and both persistences have to answer the same name for it.
 */
@Document(collection = "test4_entity")
@Setter
public class EntityWithIdOnGetter {

  private String entityId;

  private String entityValue;

  @Id
  public String getEntityId() {

    return entityId;

  }

  public String getEntityValue() {

    return entityValue;

  }

}
