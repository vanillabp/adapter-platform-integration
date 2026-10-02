package io.vanillabp.integration.test.upgradecost;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The repository VanillaBP uses for {@link CostAggregate}.
 */
public interface CostAggregateRepository extends JpaRepository<CostAggregate, Long> {
}
