package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Entity2Repository extends MongoRepository<Entity2, String> {
}
