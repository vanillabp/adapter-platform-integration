package io.vanillabp.integration.test.nativeimage;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * The workflow aggregate of the application which this module builds natively. It is a
 * plain JPA entity whose ID the application assigns, stored in a relational database.
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
