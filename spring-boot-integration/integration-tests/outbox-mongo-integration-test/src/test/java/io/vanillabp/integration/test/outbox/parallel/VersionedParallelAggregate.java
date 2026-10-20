package io.vanillabp.integration.test.outbox.parallel;

import java.time.Instant;

import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the parallel multi-instance test, with a version attribute and
 * an auditing field. Spring Data sets both while it writes. So neither of them may count as
 * a change of the aggregate, and a version which did not move proves that nothing was
 * written.
 */
@Document(collection = "versioned-parallel-test-aggregate")
@Getter
@Setter
public class VersionedParallelAggregate extends ParallelAggregateBase {

  @Version
  private Long version;

  @LastModifiedDate
  private Instant lastModified;

}
