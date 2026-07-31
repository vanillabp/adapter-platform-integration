package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MongoAggregateRepository extends MongoRepository<MongoAggregate, String> {
}
