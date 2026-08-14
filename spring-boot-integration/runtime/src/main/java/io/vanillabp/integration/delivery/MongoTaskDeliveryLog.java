package io.vanillabp.integration.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

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
 * {@link io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutbox}): a crash between
 * writing the record and committing the aggregate would then skip a redelivery of work
 * which was rolled back.
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
                    .getOutcome(), document.getBpmnErrorCode(), document.getBpmnErrorName()));

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
                      .outcome(), delivery.bpmnErrorCode(), delivery.bpmnErrorName(), Instant.now()),
          collection);
      return true;
    } catch (final DuplicateKeyException e) {
      // another node recorded the same delivery between the pre-check and the insert
      logRecordedAlready(delivery);
      return false;
    }

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
