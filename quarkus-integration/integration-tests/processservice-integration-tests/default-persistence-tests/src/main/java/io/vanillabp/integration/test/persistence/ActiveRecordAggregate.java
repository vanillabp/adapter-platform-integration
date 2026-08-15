package io.vanillabp.integration.test.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The aggregate of the Hibernate ORM Panache active record idiom: no repository
 * anywhere, the entity itself carries the persistence.
 */
@Entity
public class ActiveRecordAggregate extends PanacheEntityBase {

  @Id
  public String id;

  public String status;

}
