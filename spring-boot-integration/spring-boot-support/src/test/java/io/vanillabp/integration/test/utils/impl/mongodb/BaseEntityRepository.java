package io.vanillabp.integration.test.utils.impl.mongodb;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BaseEntityRepository extends MongoRepository<BaseEntity, String> {
}
