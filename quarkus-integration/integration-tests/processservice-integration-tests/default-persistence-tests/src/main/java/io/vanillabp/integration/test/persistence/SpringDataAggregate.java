package io.vanillabp.integration.test.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The aggregate of the Spring Data idiom (extension quarkus-spring-data-jpa),
 * managed by {@link SpringDataAggregateRepository}.
 */
@Entity
public class SpringDataAggregate {

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
