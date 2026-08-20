package io.vanillabp.integration.runtime.delivery;

import java.util.Date;
import java.util.Optional;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link TaskDeliveryLog} for Quarkus applications using MongoDB (extension
 * <code>quarkus-mongodb-client</code>) for aggregate persistence. The records live in
 * the collection {@value #DEFAULT_COLLECTION_NAME} of the database
 * <code>quarkus.mongodb.database</code> and are keyed by the delivery key (the
 * document's <code>_id</code>), so uniqueness comes for free.
 * <p>
 * <strong>One transaction where MongoDB Panache provides a session (story 70):</strong> the
 * record is written through the <code>ClientSession</code> Panache bound to the running JTA
 * transaction, so it commits with the aggregate and a rollback takes it with it.
 * <p>
 * <strong>Best-effort window (no MongoDB transaction):</strong> Without such a session the
 * record is written IMMEDIATELY instead of with the commit - the same window the MongoDB
 * outbox documents. A record whose transaction rolls back would skip a redelivery of
 * work which never happened, therefore the record is deleted best-effort when the
 * transaction ends in anything but a commit. Only a crash between writing the record and
 * that rollback leaves one behind, and its delivery is then reported as done although the
 * aggregate never changed.
 */
@ApplicationScoped
@Slf4j
public class MongoTaskDeliveryLog implements TaskDeliveryLog, PlatformDefaultStore {

  /**
   * The collection holding the records - the same name the Spring Boot MongoDB log uses,
   * so both platforms share the store layout.
   */
  public static final String DEFAULT_COLLECTION_NAME = "vanillabp-task-deliveries";

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile TaskDeliveryRetentionCleanup retentionCleanup;

  private final OpenTaskTouches touches = new OpenTaskTouches(
      DEFAULT_COLLECTION_NAME, this::refreshLastSeen);

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.MONGO;

  }

  /**
   * Whether this default log is usable: the extension registers the bean at build time,
   * but without a MongoDB client it cannot store anything.
   *
   * @return Whether a MongoDB client is available
   */
  @Override
  public boolean isAvailable() {

    return mongoClient.isResolvable();

  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily.
   *
   * @return The configuration
   */
  PhaseTwoOutboxProperties getProperties() {

    if (properties == null) {
      properties = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
          ConfigProvider
              .getConfig()
              .unwrap(SmallRyeConfig.class)
              .getConfigMapping(QuarkusMigrationAdapterProperties.class)
              .outbox());
    }
    return properties;

  }

  /**
   * Creates the index the cleanup reads (unless disabled) and starts the cleanup.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes final StartupEvent event) {

    if (!isAvailable()) {
      log.debug("No MongoDB client available - the MongoDB-based task delivery log stays inactive");
      return;
    }
    if (!getProperties().getMongo().isEnabled()) {
      log.debug("'vanillabp.outbox.mongo.enabled' is false - the MongoDB-based task delivery log stays inactive");
      return;
    }
    if (getProperties().isCreateSchema()) {
      // MongoDB answers a createIndex of an index which is already there with its name, so
      // two instances starting at the same moment do not collide over it
      deliveryCollection().createIndex(Indexes.ascending("lastSeenAt"));
    }
    retentionCleanup = new TaskDeliveryRetentionCleanup(
        DEFAULT_COLLECTION_NAME, getProperties().getRetention(), this::cleanUpExpiredRecords);
    retentionCleanup.start();

  }

  /**
   * Refreshes the records of the open tasks redelivered since the last run and deletes the
   * records nobody has seen for the retention period - run by the background cleanup and
   * usable on demand (e.g. by tests). Refreshing first is what keeps the record of a task
   * which is still being redelivered (story 97).
   *
   * @return The number of records deleted
   */
  public long cleanUpExpiredRecords() {

    touches.flush();

    return deliveryCollection()
        .deleteMany(
            new Document(
                "lastSeenAt", new Document("$lt", Date
                    .from(java.time.Instant.now().minus(getProperties().getRetention())))))
        .getDeletedCount();

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    touches.remember(deliveryKey);

  }

  /**
   * Moves <code>lastSeenAt</code> of one block of records, in one round trip. A key whose
   * record was deleted meanwhile matches nothing, which is the right answer: the record is
   * gone and the next redelivery writes a new one.
   *
   * @param deliveryKeys The keys of one block
   */
  private void refreshLastSeen(
      final java.util.List<String> deliveryKeys) {

    final var now = new Date();
    deliveryCollection()
        .bulkWrite(
            deliveryKeys
                .stream()
                .map(deliveryKey -> (WriteModel<Document>) new UpdateOneModel<Document>(
                    Filters.eq("_id", deliveryKey), Updates.set("lastSeenAt", now)))
                .toList(),
            new BulkWriteOptions().ordered(false));

  }

  @PreDestroy
  void shutdown() {

    if (retentionCleanup != null) {
      retentionCleanup.stop();
      retentionCleanup = null;
    }

  }

  @Override
  public Optional<TaskDelivery> recordedDelivery(
      final String deliveryKey) {

    // read through the session of the running transaction where there is one, so the
    // answer is consistent with what this transaction wrote (story 70)
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    return Optional
        .ofNullable(
            session != null
                ? collection
                    .find(session, new Document("_id", deliveryKey))
                    .first()
                : collection
                    .find(new Document("_id", deliveryKey))
                    .first())
        .map(document -> new TaskDelivery(
            deliveryKey, document.getString("workflowModuleId"), document.getString("bpmnProcessId"), document
                .getString("aggregateId"), document.getString("taskDefinition"), document
                    .getString("outcome"), document.getString("bpmnErrorCode"), document
                        .getString("bpmnErrorName"), document.getDate("recordedAt") == null
                            ? null
                            : document.getDate("recordedAt").toInstant()));

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    final var collection = deliveryCollection();
    // the session of the running transaction where MongoDB Panache provides one: the
    // record then commits with the aggregate instead of being written immediately
    // (story 70)
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var recordedAt = Date.from(delivery.recordedAt() == null
        ? java.time.Instant.now()
        : delivery.recordedAt());
    final var record = new Document()
        .append("_id", delivery.deliveryKey())
        .append("workflowModuleId", delivery.workflowModuleId())
        .append("bpmnProcessId", delivery.bpmnProcessId())
        .append("aggregateId", delivery.workflowAggregateId())
        .append("taskDefinition", delivery.taskDefinition())
        .append("outcome", delivery.outcome())
        .append("bpmnErrorCode", delivery.bpmnErrorCode())
        .append("bpmnErrorName", delivery.bpmnErrorName())
        .append("recordedAt", recordedAt)
        // the record was seen the moment it was written; a redelivery of a task which
        // stays open moves lastSeenAt and leaves recordedAt where it is
        .append("lastSeenAt", recordedAt);
    // a duplicate-key error inside a MongoDB transaction would abort it entirely, so the
    // common duplicate is read instead - the unique document ID stays the backstop
    if ((session != null) && (collection
        .find(session, new Document("_id", delivery.deliveryKey()))
        .first() != null)) {
      log.debug(
          "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
          delivery.deliveryKey(),
          delivery.bpmnProcessId(),
          delivery.workflowModuleId());
      return false;
    }
    try {
      if (session != null) {
        collection.insertOne(session, record);
      } else {
        collection.insertOne(record);
      }
    } catch (final MongoWriteException e) {
      // 11000 = duplicate key: another node recorded the same delivery concurrently
      if (e.getError().getCode() == 11000) {
        log.debug(
            "Task delivery '{}' of BPMN process '{}' of workflow module '{}' was recorded already",
            delivery.deliveryKey(),
            delivery.bpmnProcessId(),
            delivery.workflowModuleId());
        return false;
      }
      throw e;
    }

    // without a session MongoDB does not take part in the JTA transaction, so the record
    // is already written - remove it again if the transaction does not commit, otherwise a
    // redelivery of the rolled-back work would be skipped. With a session the abort of the
    // MongoDB transaction takes the record with it.
    if ((session == null) && (txRegistry.getTransactionKey() != null)) {
      txRegistry.registerInterposedSynchronization(new Synchronization() {
        @Override
        public void beforeCompletion() {
          // nothing to do
        }

        @Override
        public void afterCompletion(
            final int status) {
          if (status == Status.STATUS_COMMITTED) {
            return;
          }
          try {
            collection.deleteOne(new Document("_id", delivery.deliveryKey()));
          } catch (final RuntimeException e) {
            log.warn(
                "Could not delete the record of task delivery '{}' after the rollback of the local "
                    + "transaction - a redelivery of that task will be skipped although nothing was "
                    + "persisted; delete the record manually",
                delivery.deliveryKey(),
                e);
          }
        }
      });
    }

    return true;

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final java.time.Instant recordedBefore) {

    final var collection = deliveryCollection();
    // through the session of the running transaction where MongoDB Panache provides one:
    // the deletion then commits with the end notification instead of being written
    // immediately (story 70)
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    final var filter = new Document()
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("recordedAt", new Document("$lt", Date.from(recordedBefore)));
    final var result = session != null
        ? collection.deleteMany(session, filter)
        : collection.deleteMany(filter);
    return (int) result.getDeletedCount();

  }

  private MongoCollection<Document> deliveryCollection() {

    final var database = ConfigProvider
        .getConfig()
        .getOptionalValue("quarkus.mongodb.database", String.class)
        .orElseThrow(() -> new IllegalStateException(
            """
                The MongoDB-based task delivery log needs the database name! Set the property \
                'quarkus.mongodb.database' (the same database the workflow aggregates live in)."""));
    return mongoClient
        .get()
        .getDatabase(database)
        .getCollection(DEFAULT_COLLECTION_NAME);

  }

}
