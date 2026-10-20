package io.vanillabp.integration.test.outbox.parallel;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The iterations of a parallel multi-instance on an aggregate WITH a version attribute.
 * {@link #readingIteration} only reads the aggregate. {@link #recordingIteration} writes its
 * result into a record of its own and leaves the aggregate alone. {@link #writingIteration}
 * changes the aggregate.
 * <p>
 * The other methods are single tasks, one for each kind of change the test tells apart. A
 * record is changed through a reference, a reference is changed, or a sub-document is
 * changed.
 */
@Service
@WorkflowService(
    workflowAggregateClass = VersionedParallelAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "VersionedParallelProcess"))
public class VersionedParallelWorkflowService {

  /**
   * The id of a record which the aggregate does not point to when the test starts. There is
   * one with this id in each of the two collections of records.
   */
  public static final String OTHER_RECORD = "other-record";

  private final Rendezvous rendezvous;

  private final IterationRecordRepository records;

  private final LazyRecordRepository lazyRecords;

  public VersionedParallelWorkflowService(
      final Rendezvous rendezvous,
      final IterationRecordRepository records,
      final LazyRecordRepository lazyRecords) {

    this.rendezvous = rendezvous;
    this.records = records;
    this.lazyRecords = lazyRecords;

  }

  @WorkflowTask(taskDefinition = "readingIteration")
  public void readingIteration(
      final VersionedParallelAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    if (aggregate.getContent() == null) {
      throw new IllegalStateException("iteration %d read no content".formatted(index));
    }
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "recordingIteration")
  public void recordingIteration(
      final VersionedParallelAggregate aggregate,
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
      final VersionedParallelAggregate aggregate,
      @MultiInstanceIndex("Iterations") final int index) {

    aggregate.setContent("written by iteration %d".formatted(index));
    rendezvous.arrive();

  }

  @WorkflowTask(taskDefinition = "changeRecordBehindReference")
  public void changeRecordBehindReference(
      final VersionedParallelAggregate aggregate) {

    final var iterationRecord = aggregate
        .getIterations()
        .getFirst();
    iterationRecord.setResult("changed behind the reference");
    records.save(iterationRecord);

  }

  @WorkflowTask(taskDefinition = "changeRecordBehindDocumentReference")
  public void changeRecordBehindDocumentReference(
      final VersionedParallelAggregate aggregate) {

    final var iterationRecord = aggregate.getDocumentReferenced();
    iterationRecord.setResult("changed behind the reference");
    records.save(iterationRecord);

  }

  @WorkflowTask(taskDefinition = "changeRecordBehindLazyReference")
  public void changeRecordBehindLazyReference(
      final VersionedParallelAggregate aggregate) {

    final var lazyRecord = aggregate.getLazilyReferenced();
    lazyRecord.setResult("changed behind the reference");
    lazyRecords.save(lazyRecord);

  }

  @WorkflowTask(taskDefinition = "leaveLazyReferenceAlone")
  public void leaveLazyReferenceAlone(
      final VersionedParallelAggregate aggregate) {

    // the lazy reference is not touched, so nothing may resolve it

  }

  @WorkflowTask(taskDefinition = "pointToAnotherRecord")
  public void pointToAnotherRecord(
      final VersionedParallelAggregate aggregate) {

    aggregate
        .getIterations()
        .set(0, otherRecord());

  }

  @WorkflowTask(taskDefinition = "addAReference")
  public void addAReference(
      final VersionedParallelAggregate aggregate) {

    aggregate
        .getIterations()
        .add(otherRecord());

  }

  @WorkflowTask(taskDefinition = "removeAReference")
  public void removeAReference(
      final VersionedParallelAggregate aggregate) {

    aggregate
        .getIterations()
        .removeFirst();

  }

  @WorkflowTask(taskDefinition = "pointDocumentReferenceToAnotherRecord")
  public void pointDocumentReferenceToAnotherRecord(
      final VersionedParallelAggregate aggregate) {

    aggregate.setDocumentReferenced(otherRecord());

  }

  @WorkflowTask(taskDefinition = "pointLazyReferenceToAnotherRecord")
  public void pointLazyReferenceToAnotherRecord(
      final VersionedParallelAggregate aggregate) {

    aggregate
        .setLazilyReferenced(lazyRecords
            .findById(OTHER_RECORD)
            .orElseThrow());

  }

  @WorkflowTask(taskDefinition = "changeSubDocument")
  public void changeSubDocument(
      final VersionedParallelAggregate aggregate) {

    aggregate
        .getDetails()
        .setNote("changed in the sub-document");

  }

  /**
   * The record the test stores besides the ones the aggregate points to.
   */
  private IterationRecord otherRecord() {

    return records
        .findById(OTHER_RECORD)
        .orElseThrow();

  }

}
