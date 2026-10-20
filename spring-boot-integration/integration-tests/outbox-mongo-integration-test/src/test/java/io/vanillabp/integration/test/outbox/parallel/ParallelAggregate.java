package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The workflow aggregate of the parallel multi-instance test, without a version attribute.
 * Two writers of such a document meet as a MongoDB write conflict inside the transaction.
 */
@Document(collection = "parallel-test-aggregate")
public class ParallelAggregate extends ParallelAggregateBase {

}
