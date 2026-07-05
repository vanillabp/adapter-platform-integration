package io.vanillabp.integration.test.outbox;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AggregateRepository extends MongoRepository<Aggregate, String> {

}
