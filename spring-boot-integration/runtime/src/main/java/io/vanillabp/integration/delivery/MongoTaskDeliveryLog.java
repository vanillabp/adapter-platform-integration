package io.vanillabp.integration.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link TaskDeliveryLog} for Spring Boot applications persisting their
 * workflow aggregates in MongoDB. The records are written through the
 * {@link MongoTemplate} which takes part in the Spring-managed MongoDB transaction, so
 * a record and the aggregate changes of the same delivery commit together.
 * <p>
 * <strong>Note:</strong> MongoDB transactions require a replica set. Without one (no
 * <code>MongoTransactionManager</code> or a standalone server) the record is written
 * immediately, exactly like an outbox entry is (see
 * {@link io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutbox}). The record is then
 * deleted again when its transaction does not commit (story 70, the same compensation the
 * Quarkus MongoDB log has), so a redelivery of rolled-back work is not skipped - only a
 * process dying between the two leaves a record behind, and its delivery is reported as
 * done although nothing was persisted.
 */
@Slf4j
public class MongoTaskDeliveryLog implements TaskDeliveryLog {

  /**
   * The collection holding the records.
   */
  public static final String DEFAULT_COLLECTION_NAME = "vanillabp-task-deliveries";

  private final MongoTemplate mongoTemplate;

  private final String collection;

  private final Duration retention;

  private final TaskDeliveryRetentionCleanup retentionCleanup;

  public MongoTaskDeliveryLog(
      final MongoTemplate mongoTemplate,
      final String collection,
      final Duration retention) {

    this.mongoTemplate = mongoTemplate;
    this.collection = collection;
    this.retention = retention;
    this.retentionCleanup = new TaskDeliveryRetentionCleanup(
        collection, retention, this::cleanUpExpiredRecords);

  }

  /**
   * Starts the retention cleanup - called once the application context is ready.
   */
  public void start() {

    retentionCleanup.start();

  }

  /**
   * Stops the retention cleanup - called on shutdown of the application context.
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
        .map(document -> new TaskDelivery(
            document.getId(), document.getWorkflowModuleId(), document.getBpmnProcessId(), document
                .getAggregateId(), document.getTaskDefinition(), document
                    .getOutcome(), document.getBpmnErrorCode(), document
                        .getBpmnErrorName(), document.getRecordedAt()));

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    // pre-check for an existing record: within a MongoDB transaction a duplicate-key
    // error would abort the whole transaction (the aggregate changes included), so the
    // common duplicate case is detected by a read - the unique document ID stays the
    // backstop for two nodes recording the same delivery at the same time, where the
    // loser's transaction aborts and its work is rolled back
    if (mongoTemplate.exists(
        Query.query(Criteria.where("_id").is(delivery.deliveryKey())),
        TaskDeliveryDocument.class,
        collection)) {
      logRecordedAlready(delivery);
      return false;
    }

    try {
      mongoTemplate.insert(
          new TaskDeliveryDocument(
              delivery.deliveryKey(), delivery.workflowModuleId(), delivery.bpmnProcessId(), delivery
                  .workflowAggregateId(), delivery.taskDefinition(), delivery
                      .outcome(), delivery.bpmnErrorCode(), delivery.bpmnErrorName(), delivery.recordedAt() == null
                          ? Instant.now()
                          : delivery.recordedAt()),
          collection);
      compensateUnlessCommitted(delivery);
      return true;
    } catch (final DuplicateKeyException e) {
      // another node recorded the same delivery between the pre-check and the insert
      logRecordedAlready(delivery);
      return false;
    }

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
                        .and("recordedAt")
                        .lt(recordedBefore)),
            TaskDeliveryDocument.class,
            collection)
        .getDeletedCount();

  }

  /**
   * Removes the record again where the transaction it was written in did not commit
   * (story 70). Without a MongoDB transaction covering it - no
   * <code>MongoTransactionManager</code>, or a deployment which is no replica set - the
   * document is written immediately, and a record for work which was rolled back is the
   * worse of the two possible mistakes: the redelivery of that work would be skipped and
   * the workflow would wait forever. Where a MongoDB transaction does cover the write,
   * the rollback removes the document anyway and this deletion finds nothing.
   * <p>
   * Same behaviour as the Quarkus MongoDB delivery log, which compensates through a JTA
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
   * Deletes the records whose retention period passed - run by the background cleanup and
   * usable on demand (e.g. by tests).
   *
   * @return The number of records deleted
   */
  public long cleanUpExpiredRecords() {

    return mongoTemplate
        .remove(
            Query.query(Criteria.where("recordedAt").lt(Instant.now().minus(retention))),
            TaskDeliveryDocument.class,
            collection)
        .getDeletedCount();

  }

}
