package io.vanillabp.integration.test.persistence;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MongoRepositoryAggregateRepository implements PanacheMongoRepositoryBase<MongoRepositoryAggregate, String> {
}
