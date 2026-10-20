package io.vanillabp.integration.test.persistence.parallel;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IterationRecordRepository implements PanacheMongoRepositoryBase<IterationRecord, String> {
}
