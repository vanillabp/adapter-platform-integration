package io.vanillabp.integration.test.deployment.conflict;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the version-conflict acceptance test: a JPA
 * entity carrying the version attribute which turns two writers into an exception
 * instead of a silent overwrite. Under JTA that exception arrives wrapped, which is
 * what this test is about.
 */
@Entity
@Table(name = "CONFLICT_TEST_AGGREGATE")
@Getter
@Setter
public class ConflictAggregate {

  @Id
  private String id;

  private String content;

  @Version
  private Long version;

}
