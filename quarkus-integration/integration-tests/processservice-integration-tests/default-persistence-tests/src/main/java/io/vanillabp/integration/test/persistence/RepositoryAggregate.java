package io.vanillabp.integration.test.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The aggregate of the Hibernate ORM Panache repository idiom. It is a plain JPA entity
 * whose ID the application assigns. {@link RepositoryAggregateRepository} manages it.
 */
@Entity
public class RepositoryAggregate {

  @Id
  private String id;

  private String status;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(
      final String status) {
    this.status = status;
  }

}
