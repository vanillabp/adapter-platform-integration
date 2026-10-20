package io.vanillabp.integration.test.persistence.parallel;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The iterations of a parallel multi-instance on an aggregate in a MongoDB Panache
 * repository. {@link #readingIteration} only reads the aggregate. {@link #recordingIteration}
 * writes its result into a record of its own and leaves the aggregate alone.
 * {@link #writingIteration} changes it.
 * <p>
 * The other methods are single tasks, one for each kind of change the test tells apart. A
 * record is changed behind an id, an id is changed, or a sub-document is changed.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ParallelRepositoryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ParallelRepositoryProcess"))
public class ParallelRepositoryWorkflowService {

  /**
   * The id of a record which the aggregate does not point to when the test starts.
   */
  public static final String OTHER_RECORD = "other-record";

  @Inject
  Rendezvous rendezvous;

  @Inject
  IterationRecordRepository records;

  @WorkflowTask(taskDefinition = "readingIteration")
  public void readingIteration(
      final ParallelRepositoryAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    if (aggregate.getContent() == null) {
      throw new IllegalStateException("iteration %d read no content".formatted(index));
    }
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "recordingIteration")
  public void recordingIteration(
      final ParallelRepositoryAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    final var iterationRecord = records.findById(aggregate.getIterationRecordIds().get(index));
    iterationRecord.setResult("result of iteration %d".formatted(index));
    records.persistOrUpdate(iterationRecord);
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "writingIteration")
  public void writingIteration(
      final ParallelRepositoryAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    aggregate.setContent("written by iteration %d".formatted(index));
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "changeRecordBehindId")
  public void changeRecordBehindId(
      final ParallelRepositoryAggregate aggregate) {

    final var iterationRecord = records.findById(aggregate
        .getIterationRecordIds()
        .getFirst());
    iterationRecord.setResult("changed behind the id");
    records.persistOrUpdate(iterationRecord);

  }

  @WorkflowTask(taskDefinition = "pointToAnotherRecord")
  public void pointToAnotherRecord(
      final ParallelRepositoryAggregate aggregate) {

    aggregate
        .getIterationRecordIds()
        .set(0, OTHER_RECORD);

  }

  @WorkflowTask(taskDefinition = "changeSubDocument")
  public void changeSubDocument(
      final ParallelRepositoryAggregate aggregate) {

    aggregate
        .getDetails()
        .setNote("changed in the sub-document");

  }

}
