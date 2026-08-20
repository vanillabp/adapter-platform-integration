package io.vanillabp.integration.test.nativeimage;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The workflow aggregate of the application built natively by this module: a plain JPA
 * entity with an application-assigned ID, stored in a relational database.
 */
@Entity
public class OrderAggregate {

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
