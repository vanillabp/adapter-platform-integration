package io.vanillabp.integration.test.outbox.parallel;

import java.util.ArrayList;
import java.util.stream.IntStream;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores what a test of the parallel multi-instance starts from: an aggregate which points to
 * one record per iteration, to a record behind a lazy reference and to one behind a document
 * reference, and which holds a sub-document. Besides those, one record per collection exists
 * which the aggregate does not point to.
 */
@Component
public class ParallelScenario {

  private final MongoTemplate mongoTemplate;

  private final IterationRecordRepository records;

  private final LazyRecordRepository lazyRecords;

  public ParallelScenario(
      final MongoTemplate mongoTemplate,
      final IterationRecordRepository records,
      final LazyRecordRepository lazyRecords) {

    this.mongoTemplate = mongoTemplate;
    this.records = records;
    this.lazyRecords = lazyRecords;

  }

  /**
   * Empties every collection of the scenario and stores the given aggregate with its records.
   *
   * @param <A> The aggregate's type
   * @param aggregate A new aggregate, without content yet
   * @param iterations How many records the aggregate points to, one per iteration
   * @return The aggregate as it was stored
   */
  public <A extends ParallelAggregateBase> A store(
      final A aggregate,
      final int iterations) {

    mongoTemplate.dropCollection(ParallelAggregate.class);
    mongoTemplate.dropCollection(VersionedParallelAggregate.class);
    mongoTemplate.dropCollection(IterationRecord.class);
    mongoTemplate.dropCollection(LazyRecord.class);

    aggregate.setId("aggregate");
    aggregate.setContent("as started");
    aggregate
        .setIterations(new ArrayList<>(IntStream
            .range(0, iterations)
            .mapToObj(index -> records.save(iterationRecord("record-%d".formatted(index))))
            .toList()));
    aggregate.setDocumentReferenced(records.save(iterationRecord("document-record")));
    aggregate.setLazilyReferenced(lazyRecords.save(lazyRecord("lazy-record")));
    final var details = new Details();
    details.setNote("as started");
    aggregate.setDetails(details);

    records.save(iterationRecord(VersionedParallelWorkflowService.OTHER_RECORD));
    lazyRecords.save(lazyRecord(VersionedParallelWorkflowService.OTHER_RECORD));

    // read back, because MongoDB keeps time stamps in milliseconds only
    final var saved = mongoTemplate.save(aggregate);
    @SuppressWarnings("unchecked")
    final var stored = (A) mongoTemplate.findById(saved.getId(), saved.getClass());
    return stored;

  }

  private static IterationRecord iterationRecord(
      final String id) {

    final var iterationRecord = new IterationRecord();
    iterationRecord.setId(id);
    iterationRecord.setResult("none yet");
    return iterationRecord;

  }

  private static LazyRecord lazyRecord(
      final String id) {

    final var lazyRecord = new LazyRecord();
    lazyRecord.setId(id);
    lazyRecord.setResult("none yet");
    return lazyRecord;

  }

}
