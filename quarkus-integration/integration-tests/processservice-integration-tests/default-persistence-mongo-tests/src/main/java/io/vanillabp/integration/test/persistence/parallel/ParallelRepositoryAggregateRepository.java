package io.vanillabp.integration.test.persistence.parallel;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ParallelRepositoryAggregateRepository implements PanacheMongoRepositoryBase<ParallelRepositoryAggregate, String> {
}
