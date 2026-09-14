package io.vanillabp.integration.test.electioncost;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of this scenario. Reading it is what takes the database
 * connection, the way a service task takes one before it reports anything.
 */
@Entity
@Table(name = "COST_AGGREGATE")
@Getter
@Setter
public class CostAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
