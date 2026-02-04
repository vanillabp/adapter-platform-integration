package io.vanillabp.integration.test.utils.impl.jpa;

import jakarta.persistence.Column;

public class DerivedEntity extends BaseEntity {

  @Column
  private String entityValue;

}
