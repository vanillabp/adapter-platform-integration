package io.vanillabp.integration.test.persistence.parallel;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The iterations of a parallel multi-instance on a MongoDB Panache active record.
 * {@link #readingIteration} only reads the aggregate. {@link #recordingIteration} writes
 * its result into a record of its own and leaves the aggregate alone.
 * {@link #writingIteration} changes it.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ParallelActiveRecordAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ParallelActiveRecordProcess"))
public class ParallelActiveRecordWorkflowService {

  @Inject
  Rendezvous rendezvous;

  @Inject
  IterationRecordRepository records;

  @WorkflowTask(taskDefinition = "readingIteration")
  public void readingIteration(
      final ParallelActiveRecordAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    if (aggregate.content == null) {
      throw new IllegalStateException("iteration %d read no content".formatted(index));
    }
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "recordingIteration")
  public void recordingIteration(
      final ParallelActiveRecordAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    final var iterationRecord = records.findById(aggregate.iterationRecordIds.get(index));
    iterationRecord.setResult("result of iteration %d".formatted(index));
    records.persistOrUpdate(iterationRecord);
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "writingIteration")
  public void writingIteration(
      final ParallelActiveRecordAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    aggregate.content = "written by iteration %d".formatted(index);
    rendezvous.arrive();

  }

}
