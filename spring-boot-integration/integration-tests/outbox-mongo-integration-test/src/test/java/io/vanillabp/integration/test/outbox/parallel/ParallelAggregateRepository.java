package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ParallelAggregateRepository extends MongoRepository<ParallelAggregate, String> {

}
