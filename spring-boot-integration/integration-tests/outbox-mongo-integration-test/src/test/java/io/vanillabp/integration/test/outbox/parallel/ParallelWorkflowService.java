package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The iterations of a parallel multi-instance on an aggregate WITHOUT a version attribute.
 * {@link #readingIteration} only reads the aggregate. {@link #recordingIteration} writes its
 * result into a record of its own and leaves the aggregate alone. {@link #writingIteration}
 * changes the aggregate.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ParallelAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ParallelProcess"))
public class ParallelWorkflowService {

  private final Rendezvous rendezvous;

  private final IterationRecordRepository records;

  public ParallelWorkflowService(
      final Rendezvous rendezvous,
      final IterationRecordRepository records) {

    this.rendezvous = rendezvous;
    this.records = records;

  }

  @WorkflowTask(taskDefinition = "readingIteration")
  public void readingIteration(
      final ParallelAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    if (aggregate.getContent() == null) {
      throw new IllegalStateException("iteration %d read no content".formatted(index));
    }
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "recordingIteration")
  public void recordingIteration(
      final ParallelAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    final var iterationRecord = aggregate
        .getIterations()
        .get(index);
    iterationRecord.setResult("result of iteration %d".formatted(index));
    records.save(iterationRecord);
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "writingIteration")
  public void writingIteration(
      final ParallelAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    aggregate.setContent("written by iteration %d".formatted(index));
    rendezvous.arrive();

  }

}
