package io.vanillabp.integration.test.deployment.conflict;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The workflow aggregate of the version-conflict acceptance test (story 59): a JPA
 * entity carrying the version attribute which turns two writers into an exception
 * instead of a silent overwrite. Under JTA that exception arrives wrapped, which is
 * what this test is about.
 */
@Entity
@Table(name = "CONFLICT_TEST_AGGREGATE")
public class ConflictAggregate {

  @Id
  private String id;

  private String content;

  @Version
  private Long version;

  public String getId() {
    return id;
  }

  public void setId(
      final String id) {
    this.id = id;
  }

  public String getContent() {
    return content;
  }

  public void setContent(
      final String content) {
    this.content = content;
  }

  public Long getVersion() {
    return version;
  }

  public void setVersion(
      final Long version) {
    this.version = version;
  }

}
