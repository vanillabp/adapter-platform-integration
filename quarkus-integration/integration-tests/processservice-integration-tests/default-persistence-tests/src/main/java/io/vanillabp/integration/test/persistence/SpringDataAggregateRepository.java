package io.vanillabp.integration.test.persistence;

import org.springframework.data.repository.CrudRepository;

public interface SpringDataAggregateRepository extends CrudRepository<SpringDataAggregate, String> {
}
