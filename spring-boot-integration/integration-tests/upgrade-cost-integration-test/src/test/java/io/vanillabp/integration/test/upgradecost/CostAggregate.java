package io.vanillabp.integration.test.upgradecost;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of this scenario. The outbox also orders by its id. So this id
 * decides on which dispatch thread an entry of this aggregate travels.
 */
@Entity
@Table(name = "UPGRADE_COST_AGGREGATE")
@Getter
@Setter
public class CostAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
