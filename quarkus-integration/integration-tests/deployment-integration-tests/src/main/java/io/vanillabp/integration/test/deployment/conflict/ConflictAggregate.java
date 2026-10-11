package io.vanillabp.integration.test.deployment.conflict;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the version-conflict acceptance test. It is a JPA entity with
 * a version attribute. With it, two writers cause an exception instead of a silent
 * overwrite. Under JTA (the Jakarta Transactions API) that exception arrives wrapped, and
 * this test is about that.
 */
@Entity
@Table(name = "CONFLICT_TEST_AGGREGATE")
@Getter
@Setter
public class ConflictAggregate {

  @Id
  private String id;

  private String content;

  // the test reads this field, but no BPMN model does, so it stays out of the BPMS

  @NoSyncWithBPMS

  @Version
  private Long version;

}
