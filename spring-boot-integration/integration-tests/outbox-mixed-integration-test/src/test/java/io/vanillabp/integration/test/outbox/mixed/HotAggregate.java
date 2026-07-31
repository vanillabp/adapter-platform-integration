package io.vanillabp.integration.test.outbox.mixed;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A JPA workflow aggregate of a "high-load" process: a
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware} bean assigns it a
 * DEDICATED outbox on its own table (<code>HOT_OUTBOX</code>) to isolate it from the
 * other processes' outbox traffic.
 */
@Entity
@Table(name = "MIXED_HOT_AGGREGATE")
@Getter
@Setter
public class HotAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
