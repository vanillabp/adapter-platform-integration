package io.vanillabp.integration.test.mixed;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The aggregate which this application stores in its relational database. It is written as
 * a Hibernate ORM Panache active record.
 */
@Entity
public class JpaAggregate extends PanacheEntityBase {

  @Id
  public String id;

  public String status;

}
