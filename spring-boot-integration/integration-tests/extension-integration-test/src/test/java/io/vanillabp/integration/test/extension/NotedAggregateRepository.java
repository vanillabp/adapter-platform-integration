package io.vanillabp.integration.test.extension;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The repository VanillaBP uses for {@link NotedAggregate}. The tests read through it
 * to see what a handler call saved.
 */
public interface NotedAggregateRepository extends JpaRepository<NotedAggregate, Long> {
}
