package io.vanillabp.integration.test.outbox.conflict;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the version-conflict acceptance test (story 59): a JPA
 * entity carrying the version attribute which turns two writers into an exception
 * instead of a silent overwrite.
 */
@Entity
@Table(name = "CONFLICT_TEST_AGGREGATE")
@Getter
@Setter
public class ConflictAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

  @Version
  private Long version;

}
