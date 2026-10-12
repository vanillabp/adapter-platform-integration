package io.vanillabp.integration.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.data.util.Pair;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link TaskDeliveryLog} for Spring Boot applications which persist their
 * workflow aggregates in MongoDB. The records are written through the
 * {@link MongoTemplate}, which takes part in the Spring-managed MongoDB transaction. So a
 * record and the aggregate changes of the same delivery commit together.
 * <p>
 * The collection is the one <code>vanillabp.outbox.mongo.delivery-collection</code> names.
 * Its default is
 * {@value io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties#DEFAULT_DELIVERY_COLLECTION}.
 * <p>
 * <strong>Note:</strong> MongoDB transactions need a replica set. Without a MongoDB
 * transaction (no <code>MongoTransactionManager</code>, or a standalone server), the
 * record is written at once, exactly like an outbox entry (see
 * {@link io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutbox}). The record is then
 * deleted again when its transaction does not commit. The Quarkus MongoDB log has the
 * same compensation. So a redelivery of work which was rolled back is not skipped. Only a
 * process which dies between the two leaves a record behind. Its delivery is then
 * reported as done, although nothing was persisted.
 */
@Slf4j
public class MongoTaskDeliveryLog implements TaskDeliveryLog {

  /**
   * The outcome of a delivery which left its task open. The questions about open tasks
   * only care about these records.
   */
  private static final String COMPLETION_PENDING = WorkflowTaskOutcome.Kind.COMPLETION_PENDING
      .name();

  /**
   * The kind of document which says where a workflow runs. Every question about open work
   * demands that a document is NOT this kind. It does not demand that it is a delivery.
   * MongoDB has no statement which fills a field of the documents already there. So a
   * document written before the field existed has none. <code>$ne</code> matches a
   * document whose field is absent, and <code>$eq</code> does not. The relational store
   * asks the other way round, because the ALTER which adds the column fills it.
   */
  private static final String WORKFLOW_START = io.vanillabp.integration.spi.DeliveryRecordKind.WORKFLOW_START
      .name();

  private final MongoTemplate mongoTemplate;

  private final String collection;

  private final Duration retention;

  private final Duration workflowStartRetention;

  private final io.vanillabp.integration.spi.WorkflowStartSieve workflowStartSieve;

  /**
   * How many documents about started workflows one sieved run looks at. The sieve costs one
   * read of the application's own database per document. So a run has an upper bound, and
   * does not take all the documents which expired while the application was stopped. The
   * next hourly run continues, because what it kept is kept and what it deleted is gone.
   */
  private static final int WORKFLOW_STARTS_SIEVED_PER_RUN = 1000;

  private final TaskDeliveryRetentionCleanup retentionCleanup;

  private final OpenTaskTouches touches;

  /**
   * Builds the log, and nothing more. The auto-configuration creates the indexes. The
   * cleanup waits for {@link #start()}, which the auto-configuration calls once every
   * singleton exists.
   *
   * @param mongoTemplate The template the records are written and read through. The
   *          aggregates use the same one. So both take part in one MongoDB transaction
   *          wherever the deployment is a replica set
   * @param collection The collection the records go into
   * @param retention How long a record which nobody saw again is kept. In this window, a
   *          redelivery is answered from the record and the <code>@WorkflowTask</code>
   *          method does not run again. That is why it is a setting of its own, see
   *          decision 24 in the repository's DECISIONS.md
   * @param workflowStartRetention How long the document about a started workflow is kept,
   *          counted from the start. It is a period of its own, because that document is
   *          read as long as somebody may ask which workflow an aggregate belongs to
   * @param workflowStartSieve Decides per document whether it may really go once that
   *          period passed. <code>null</code> where the application did not ask for the
   *          second sieve
   */
  public MongoTaskDeliveryLog(
      final MongoTemplate mongoTemplate,
      final String collection,
      final Duration retention,
      final Duration workflowStartRetention,
      final io.vanillabp.integration.spi.WorkflowStartSieve workflowStartSieve) {

    this.mongoTemplate = mongoTemplate;
    this.collection = collection;
    this.retention = retention;
    this.workflowStartRetention = workflowStartRetention;
    this.workflowStartSieve = workflowStartSieve;
    this.retentionCleanup = new TaskDeliveryRetentionCleanup(
        collection, retention, workflowStartRetention, this::cleanUpExpiredRecords);
    this.touches = new OpenTaskTouches(collection, this::refreshLastSeen);

  }

  /**
   * Starts the retention cleanup. The auto-configuration calls it once every singleton
   * exists, which is before the application context is ready.
   */
  public void start() {

    retentionCleanup.start();

  }

  /**
   * Stops the retention cleanup. It is called on shutdown of the application context.
   */
  public void stop() {

    retentionCleanup.stop();

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    return Optional
        .ofNullable(
            mongoTemplate
                .findById(deliveryKey, TaskDeliveryDocument.class, collection))
        .map(MongoTaskDeliveryLog::recordOf);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    // check for an existing record first. Within a MongoDB transaction, a duplicate-key
    // error would abort the whole transaction, including the aggregate changes. So a read
    // finds the common duplicate case. The unique document ID stays the safety net when
    // two nodes record the same delivery at the same time. Then the transaction of the
    // loser aborts, and its work is rolled back
    if (mongoTemplate.exists(
        Query.query(Criteria.where("_id").is(delivery.deliveryKey())),
        TaskDeliveryDocument.class,
        collection)) {
      logRecordedAlready(delivery);
      return false;
    }

    // this gives the hourly cleanup something to do. A store which nobody wrote to has
    // nothing left to delete that an earlier run did not delete already
    retentionCleanup.aDeliveryWasRecorded();
    try {
      // the record was seen the moment it was written. A redelivery of a task which stays
      // open moves lastSeenAt and leaves recordedAt as it is
      final var recordedAt = delivery.recordedAt() == null
          ? Instant.now()
          : delivery.recordedAt();
      mongoTemplate.insert(
          // taskClosedAt stays absent. A record starts open. The moment the application's
          // completion reached the BPMS is only known long after the handler ran
          new TaskDeliveryDocument(
              delivery.deliveryKey(), delivery.adapterId(), delivery.workflowModuleId(), delivery
                  .bpmnProcessId(), delivery.workflowAggregateId(), delivery.workflowId(), delivery
                      .taskDefinition(), delivery.bpmnElementId(), delivery.taskId(), delivery
                          .outcome(), delivery.bpmnErrorCode(), delivery
                              .bpmnErrorName(), recordedAt, recordedAt, null, delivery
                                  .taskKind(), delivery.recordKind(), delivery.processVersion()),
          collection);
      compensateUnlessCommitted(delivery);
      return true;
    } catch (final DuplicateKeyException e) {
      // another node recorded the same delivery between the pre-check and the insert
      logRecordedAlready(delivery);
      return false;
    }

  }

  /**
   * Writes the document about the start of a workflow. It replaces the document of an earlier
   * workflow of the same aggregate (see {@link TaskDeliveryLog#recordWorkflowStart}).
   * <p>
   * The insert comes first, because usually there is no document: an aggregate carries one
   * workflow. The update only runs where the insert finds one. That update only touches a
   * document which is a start and whose workflow id differs. So a start dispatched twice
   * writes nothing. <code>lastSeenAt</code> moves with the id, because the period of such a
   * document counts from it. The version moves too. The version of the workflow which ended
   * says nothing about the one which runs now. A document which already names this workflow
   * may still get the version it lacks, and nothing else.
   */
  @Override
  public boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    if (record(workflowStart)) {
      return true;
    }
    final var startedAt = workflowStart.recordedAt() == null
        ? Instant.now()
        : workflowStart.recordedAt();
    final var replaced = mongoTemplate
        .updateFirst(
            Query
                .query(
                    Criteria
                        .where("_id")
                        .is(workflowStart.deliveryKey())
                        .and("recordKind")
                        .is(WORKFLOW_START)
                        .and("workflowId")
                        .ne(workflowStart.workflowId())),
            Update
                .update("workflowId", workflowStart.workflowId())
                .set("adapterId", workflowStart.adapterId())
                .set("recordedAt", startedAt)
                .set("lastSeenAt", startedAt)
                .set("processVersion", workflowStart.processVersion()),
            TaskDeliveryDocument.class,
            collection)
        .getModifiedCount() > 0;
    if (replaced || (workflowStart.processVersion() == null)) {
      return replaced;
    }
    // the same workflow reported a second time: by the BPMS where the adapter started it,
    // or the other way round. Only one of the two may have known the version
    return mongoTemplate
        .updateFirst(
            Query
                .query(
                    Criteria
                        .where("_id")
                        .is(workflowStart.deliveryKey())
                        .and("recordKind")
                        .is(WORKFLOW_START)
                        .and("workflowId")
                        .is(workflowStart.workflowId())
                        .and("processVersion")
                        .is(null)),
            Update.update("processVersion", workflowStart.processVersion()),
            TaskDeliveryDocument.class,
            collection)
        .getModifiedCount() > 0;

  }

  /**
   * The adapter ids which the OPEN records of one BPMN process belong to. It is asked once
   * per BPMN process at startup. So the startup can name an adapter id which such a record
   * still belongs to, but which the configuration does not know any more.
   */
  @Override
  public Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var query = Query
        .query(
            Criteria
                .where("workflowModuleId")
                .is(workflowModuleId)
                .and("bpmnProcessId")
                .is(bpmnProcessId)
                .and("recordKind")
                .ne(WORKFLOW_START)
                .and("outcome")
                .is(COMPLETION_PENDING)
                .and("adapterId")
                .ne(null));
    return new LinkedHashSet<>(
        mongoTemplate.findDistinct(query, "adapterId", collection, String.class));

  }

  /**
   * The record which left one task open, whether or not the task has been closed since. The
   * BPMS election of a task operation reads it, and does not ask a BPMS (see
   * {@link TaskDeliveryLog#recordOfTask}). It is read through the template of the running
   * transaction. It is sorted, so the most recent record answers where a task was delivered
   * more than once.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    final var query = Query
        .query(
            Criteria
                .where("taskId")
                .is(taskId)
                .and("workflowModuleId")
                .is(workflowModuleId)
                .and("bpmnProcessId")
                .is(bpmnProcessId)
                .and("aggregateId")
                .is(workflowAggregateId)
                .and("outcome")
                .is(COMPLETION_PENDING))
        .with(
            Sort.by(Sort.Direction.DESC, "recordedAt"))
        .limit(1);
    return Optional
        .ofNullable(mongoTemplate.findOne(query, TaskDeliveryDocument.class, collection))
        .map(MongoTaskDeliveryLog::recordOf);

  }

  /**
   * The open tasks of one workflow aggregate (see
   * {@link TaskDeliveryLog#openTasksOfAggregate}), oldest first. Open means: the outcome
   * left the task to the application, and no moment says its completion reached the BPMS.
   * They are read through the template of the running transaction. The index over
   * <code>aggregateId</code>, which the startup creates, serves the query.
   */
  @Override
  public List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var query = Query
        .query(
            Criteria
                .where("workflowModuleId")
                .is(workflowModuleId)
                .and("bpmnProcessId")
                .is(bpmnProcessId)
                .and("aggregateId")
                .is(workflowAggregateId)
                .and("recordKind")
                .ne(WORKFLOW_START)
                .and("outcome")
                .is(COMPLETION_PENDING)
                .and("taskClosedAt")
                .is(null))
        .with(
            Sort.by(Sort.Direction.ASC, "recordedAt"));
    return mongoTemplate
        .find(query, TaskDeliveryDocument.class, collection)
        .stream()
        .map(MongoTaskDeliveryLog::recordOf)
        .toList();

  }

  /**
   * The open tasks of one workflow of the BPMS (see
   * {@link TaskDeliveryLog#openTasksOfWorkflow}), oldest first. They are read through the
   * template of the running transaction. The index over <code>workflowId</code>, which the
   * startup creates, serves the query.
   */
  @Override
  public List<TaskDelivery> openTasksOfWorkflow(
      final String workflowModuleId,
      final String workflowId) {

    final var query = Query
        .query(
            Criteria
                .where("workflowModuleId")
                .is(workflowModuleId)
                .and("workflowId")
                .is(workflowId)
                .and("recordKind")
                .ne(WORKFLOW_START)
                .and("outcome")
                .is(COMPLETION_PENDING)
                .and("taskClosedAt")
                .is(null))
        .with(
            Sort.by(Sort.Direction.ASC, "recordedAt"));
    return mongoTemplate
        .find(query, TaskDeliveryDocument.class, collection)
        .stream()
        .map(MongoTaskDeliveryLog::recordOf)
        .toList();

  }

  /**
   * Writes down that one task is over. The filter demands an absent
   * <code>taskClosedAt</code>, so a repeated dispatch does not move the moment the task was
   * closed.
   * <p>
   * <code>updateMulti</code> and not <code>updateFirst</code>: a task may carry more than one
   * record. A record left open keeps the task alive for everything which reads the open
   * work (see {@link TaskDeliveryLog#markTaskClosed} and decision 72 in the repository's
   * DECISIONS.md).
   */
  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    return (int) mongoTemplate
        .updateMulti(
            Query
                .query(
                    Criteria
                        .where("taskId")
                        .is(taskId)
                        .and("workflowModuleId")
                        .is(workflowModuleId)
                        .and("bpmnProcessId")
                        .is(bpmnProcessId)
                        .and("aggregateId")
                        .is(workflowAggregateId)
                        .and("taskClosedAt")
                        .is(null)),
            Update.update("taskClosedAt", Instant.now()),
            TaskDeliveryDocument.class,
            collection)
        .getModifiedCount();

  }

  /**
   * The record one document holds.
   *
   * @param document The document read
   * @return What VanillaBP remembers about that delivery
   */
  private static TaskDelivery recordOf(
      final TaskDeliveryDocument document) {

    return new TaskDelivery(document.getId(), document.getAdapterId(), document.getWorkflowModuleId(), document
        .getBpmnProcessId(), document.getAggregateId(), document.getWorkflowId(), document.getTaskDefinition(), document
            .getBpmnElementId(), document
                .getTaskId(), document.getOutcome(), document.getBpmnErrorCode(), document
                    .getBpmnErrorName(), document.getRecordedAt(), document
                        .getTaskClosedAt(), document.getTaskKind(), document.getRecordKind(), document
                            .getProcessVersion());

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return mongoTemplate
        .exists(
            Query
                .query(
                    Criteria
                        .where("workflowModuleId")
                        .is(workflowModuleId)
                        .and("bpmnProcessId")
                        .is(bpmnProcessId)
                        .and("recordKind")
                        .ne(WORKFLOW_START)
                        .and("outcome")
                        .is(COMPLETION_PENDING)),
            collection);

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    return (int) mongoTemplate
        .remove(
            Query
                .query(
                    Criteria
                        .where("workflowModuleId")
                        .is(workflowModuleId)
                        .and("bpmnProcessId")
                        .is(bpmnProcessId)
                        .and("aggregateId")
                        .is(workflowAggregateId)
                        .and("recordKind")
                        .ne(WORKFLOW_START)
                        .and("recordedAt")
                        .lt(recordedBefore)),
            TaskDeliveryDocument.class,
            collection)
        .getDeletedCount();

  }

  /**
   * Removes the record again where the transaction it was written in did not commit.
   * Sometimes no MongoDB transaction covers the write: there is no
   * <code>MongoTransactionManager</code>, or the deployment is no replica set. Then the
   * document is written at once. A record for work which was rolled back is the worse of
   * the two possible mistakes. The redelivery of that work would be skipped, and the
   * workflow would wait forever. Where a MongoDB transaction does cover the write, the
   * rollback removes the document anyway, and this deletion finds nothing.
   * <p>
   * The Quarkus MongoDB delivery log behaves the same. It compensates through a JTA
   * synchronization.
   */
  private void compensateUnlessCommitted(
      final TaskDelivery delivery) {

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

      @Override
      public void afterCompletion(
          final int status) {

        if (status == TransactionSynchronization.STATUS_COMMITTED) {
          return;
        }
        try {
          mongoTemplate.remove(
              Query.query(Criteria.where("_id").is(delivery.deliveryKey())),
              TaskDeliveryDocument.class,
              collection);
        } catch (final RuntimeException e) {
          log.error(
              "The delivery record '{}' could not be removed after its transaction did not commit! "
                  + "A repeated delivery of that task will be skipped although nothing was "
                  + "persisted - remove the document from collection '{}' manually.",
              delivery.deliveryKey(),
              collection,
              e);
        }
      }

    });

  }

  private void logRecordedAlready(
      final TaskDelivery delivery) {

    log.debug(
        "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
        delivery.deliveryKey(),
        delivery.bpmnProcessId(),
        delivery.workflowModuleId());

  }

  /**
   * Refreshes the records of the open tasks which were redelivered since the last run. Then
   * it deletes the records which nobody has seen for the retention period. The background
   * cleanup runs it, and it can be called on demand, e.g. by tests. Refreshing first keeps
   * the record of a task which is still being redelivered.
   *
   * @return The number of records deleted
   */
  public long cleanUpExpiredRecords() {

    touches.flush();

    final var expiredDeliveries = mongoTemplate
        .remove(
            Query
                .query(
                    Criteria
                        .where("lastSeenAt")
                        .lt(Instant.now().minus(retention))
                        .and("recordKind")
                        .ne(WORKFLOW_START)),
            TaskDeliveryDocument.class,
            collection)
        .getDeletedCount();
    return expiredDeliveries + cleanUpExpiredWorkflowStarts();

  }

  /**
   * Deletes the documents about started workflows whose own period passed. It is a period of
   * its own, because such a document is read as long as somebody may ask which workflow an
   * aggregate belongs to. That outlasts the workflow itself.
   * <p>
   * Without a sieve, this is one delete. With a sieve, the expired documents are read first,
   * and the sieve is asked per document. Each time, that costs a read of the application's
   * own database (see {@link io.vanillabp.integration.spi.WorkflowStartSieve}). A document
   * which the sieve keeps stays where it is, and it is offered again at the next run.
   *
   * @return The number of documents deleted
   */
  private long cleanUpExpiredWorkflowStarts() {

    if ((workflowStartRetention == null) || workflowStartRetention.isZero()) {
      // a zero period says the documents about started workflows are kept for good
      return 0;
    }
    final var expired = Query
        .query(
            Criteria
                .where("lastSeenAt")
                .lt(Instant.now().minus(workflowStartRetention))
                .and("recordKind")
                .is(WORKFLOW_START));
    if (workflowStartSieve == null) {
      return mongoTemplate
          .remove(expired, TaskDeliveryDocument.class, collection)
          .getDeletedCount();
    }
    final var mayGo = mongoTemplate
        .find(expired.limit(WORKFLOW_STARTS_SIEVED_PER_RUN), TaskDeliveryDocument.class, collection)
        .stream()
        .map(MongoTaskDeliveryLog::recordOf)
        .filter(row -> Boolean.TRUE.equals(workflowStartSieve.mayBeDeleted(row)))
        .map(TaskDelivery::deliveryKey)
        .toList();
    if (mayGo.isEmpty()) {
      return 0;
    }
    return mongoTemplate
        .remove(
            Query.query(Criteria.where("_id").in(mayGo)),
            TaskDeliveryDocument.class,
            collection)
        .getDeletedCount();

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    retentionCleanup.aDeliveryWasRecorded();
    touches.remember(deliveryKey);

  }

  /**
   * Moves <code>lastSeenAt</code> of one block of records, in one unordered bulk write. A
   * key whose record was deleted in the meantime matches nothing. That is the right answer:
   * the record is gone, and the next redelivery writes a new one.
   *
   * @param deliveryKeys The keys of one block
   */
  private void refreshLastSeen(
      final List<String> deliveryKeys) {

    final var now = Instant.now();
    mongoTemplate
        .bulkOps(BulkOperations.BulkMode.UNORDERED, TaskDeliveryDocument.class, collection)
        .updateOne(
            deliveryKeys
                .stream()
                .map(deliveryKey -> Pair
                    .of(
                        Query.query(Criteria.where("_id").is(deliveryKey)),
                        (UpdateDefinition) Update.update("lastSeenAt", now)))
                .toList())
        .execute();

  }

}
