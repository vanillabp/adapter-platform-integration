package io.vanillabp.integration.test.outbox.mixed;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A JPA workflow aggregate: its outbox entries have to land in the JDBC (gruelbox)
 * outbox, riding the JPA transaction.
 */
@Entity
@Table(name = "MIXED_JPA_AGGREGATE")
@Getter
@Setter
public class JpaAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
