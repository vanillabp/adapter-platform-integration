package io.vanillabp.integration.runtime.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.adapter.migration.delivery.TaskDeliveryRetentionCleanup;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.mongo.MongoIndexes;
import io.vanillabp.integration.runtime.mongo.MongoSessions;
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
 * The default {@link TaskDeliveryLog} for Quarkus applications which use MongoDB (extension
 * <code>quarkus-mongodb-client</code>) to persist aggregates. The records live in the
 * collection which <code>vanillabp.outbox.mongo.delivery-collection</code> names, in the
 * database <code>quarkus.mongodb.database</code>. Their key is the delivery key (the
 * document's <code>_id</code>). So they are unique without any extra work.
 * <p>
 * <strong>One transaction where MongoDB Panache provides a session:</strong> the record is
 * written through the <code>ClientSession</code> which Panache bound to the running JTA
 * transaction. So it commits with the aggregate, and a rollback removes it too.
 * <p>
 * <strong>A best-effort window (no MongoDB transaction):</strong> Without such a session, the
 * record is written AT ONCE and not with the commit. The MongoDB outbox documents the same
 * window. A record whose transaction rolls back would skip a repeated delivery of work which
 * never happened. So the record is deleted, as far as possible, when the transaction ends in
 * anything but a commit. Only a crash between writing the record and that rollback leaves a
 * record behind. Its delivery is then reported as done, although the aggregate never
 * changed.
 */
@ApplicationScoped
@Slf4j
public class MongoTaskDeliveryLog implements TaskDeliveryLog, PlatformDefaultStore {

  /**
   * The outcome of a delivery which left its task open. The questions about open tasks look
   * only at these records.
   */
  private static final String COMPLETION_PENDING = WorkflowTaskOutcome.Kind.COMPLETION_PENDING
      .name();

  /**
   * The kind of document which says where a workflow runs. Every question about open work asks
   * for documents which are NOT of this kind. It does not ask for documents which are
   * deliveries. MongoDB has no statement which fills a field of the documents which are already
   * there. And <code>$ne</code> matches a document whose field is missing, but <code>$eq</code>
   * does not. The relational store asks the other way round, because the ALTER which adds its
   * column also fills it.
   */
  private static final String WORKFLOW_START = io.vanillabp.integration.spi.DeliveryRecordKind.WORKFLOW_START
      .name();

  /**
   * How many documents about started workflows one run with the sieve looks at. The sieve costs
   * one read of the application's own database per document. So a run has an upper limit. The
   * next hourly run goes on from there, because what a run kept is kept and what it deleted is
   * gone.
   */
  private static final int WORKFLOW_STARTS_SIEVED_PER_RUN = 1000;

  @Inject
  Instance<MongoClient> mongoClient;

  /**
   * Where the process services are collected. The sieve of the workflow-start documents asks
   * it, and only where the application switched that sieve on. It is an {@link Instance} and
   * not the bean itself, because the router is built from the process-service beans.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter> phaseTwoRouter;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile Duration deliveryRetention;

  private volatile Duration workflowStartRetention;

  private volatile io.vanillabp.integration.spi.WorkflowStartSieve workflowStartSieve;

  private volatile TaskDeliveryRetentionCleanup retentionCleanup;

  private volatile OpenTaskTouches touches;

  /**
   * The CDI container creates it. The extension registers this bean whether or not the
   * application has a MongoDB client. So nothing may be read or opened here.
   * {@link #isAvailable()} decides later whether the bean is used at all.
   */
  public MongoTaskDeliveryLog() {
  }

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.MONGO;

  }

  /**
   * Whether this default log can be used. The extension registers the bean at build time.
   * But without a MongoDB client, it cannot store anything.
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
   * The collection which the records live in: what
   * <code>vanillabp.outbox.mongo.delivery-collection</code> says. It is read through the
   * lazily loaded configuration above. This bean must not touch the <code>vanillabp.*</code>
   * tree before the adapter extensions registered their overlays.
   *
   * @return The name of the delivery-log collection
   */
  String deliveryCollectionName() {

    return getProperties()
        .getMongo()
        .getDeliveryCollection();

  }

  /**
   * The block of open tasks whose records are refreshed in one round trip. It is created on
   * first use and not with this bean. It is named after the collection, and that name comes
   * from the configuration which this bean reads lazily.
   *
   * @return The block, the same one for every caller
   */
  private synchronized OpenTaskTouches touches() {

    if (touches == null) {
      touches = new OpenTaskTouches(deliveryCollectionName(), this::refreshLastSeen);
    }
    return touches;

  }

  /**
   * How long a record is kept. It is <code>vanillabp.delivery.retention</code> where the
   * application sets it, and <code>vanillabp.outbox.retention</code> otherwise. The number
   * lived there before the two windows were told apart. It is loaded lazily, like the outbox
   * configuration next to it.
   *
   * Public, because it answers the first question of a support case about a handler which
   * ran twice. And the tests of this wiring assert it.
   *
   * @return The retention of delivery records
   */
  public Duration getDeliveryRetention() {

    if (deliveryRetention == null) {
      deliveryRetention = DeliveryProperties
          .resolveRetention(
              QuarkusMigrationAdapterPropertiesMapper.INSTANCE
                  .toCore(
                      ConfigProvider
                          .getConfig()
                          .unwrap(SmallRyeConfig.class)
                          .getConfigMapping(QuarkusMigrationAdapterProperties.class)
                          .delivery()),
              getProperties().getRetention());
    }
    return deliveryRetention;

  }

  /**
   * Creates the indexes which this log reads by. Where the application manages its schema
   * itself, it names the missing ones instead. Then it starts the cleanup.
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
    // What each index is read by is described once, in the core, because the Spring Boot
    // integration creates the same ones.
    if (getProperties().isCreateSchema()) {
      MongoIndexes.createOn(deliveryCollection(), MongoSchema.DELIVERY_INDEXES);
    } else {
      // The collection itself needs no check. MongoDB creates it with the first document.
      // So an application which manages its own schema only owes the indexes.
      MongoIndexes.reportMissingOn(deliveryCollection(), MongoSchema.DELIVERY_INDEXES);
    }
    retentionCleanup = new TaskDeliveryRetentionCleanup(
        deliveryCollectionName(), getDeliveryRetention(), getWorkflowStartRetention(), this::cleanUpExpiredRecords);
    retentionCleanup.start();

  }

  /**
   * Refreshes the records of the open tasks which were delivered again since the last run.
   * Then it deletes the records which nobody has seen for the retention period. The cleanup in
   * the background runs it, and others (e.g. tests) may call it when they need it. Refreshing
   * first keeps the record of a task which is still being delivered again.
   *
   * @return The number of records deleted
   */
  public long cleanUpExpiredRecords() {

    touches().flush();

    final var expiredDeliveries = deliveryCollection()
        .deleteMany(
            new Document(
                "lastSeenAt", new Document("$lt", Date
                    .from(Instant.now().minus(getDeliveryRetention()))))
                .append("recordKind", new Document("$ne", WORKFLOW_START)))
        .getDeletedCount();
    return expiredDeliveries + cleanUpExpiredWorkflowStarts();

  }

  /**
   * Deletes the documents about started workflows whose own period has passed. They have a
   * period of their own. Such a document is read as long as somebody may ask which workflow an
   * aggregate belongs to, and that is longer than the workflow lives.
   * <p>
   * Without a sieve, this is one delete. With a sieve, the expired documents are read first,
   * and the sieve is asked per document. Each time, this costs a read of the application's own
   * database (see {@link io.vanillabp.integration.spi.WorkflowStartSieve}). A document which
   * the sieve keeps stays where it is. The next run offers it again.
   *
   * @return The number of documents deleted
   */
  private long cleanUpExpiredWorkflowStarts() {

    final var retention = getWorkflowStartRetention();
    if (retention.isZero()) {
      // A zero period says that the documents about started workflows are kept forever.
      return 0;
    }
    final var expired = new Document(
        "lastSeenAt", new Document("$lt", Date.from(Instant.now().minus(retention))))
        .append("recordKind", WORKFLOW_START);
    final var sieve = getWorkflowStartSieve();
    if (sieve == null) {
      return deliveryCollection()
          .deleteMany(expired)
          .getDeletedCount();
    }
    final var mayGo = new java.util.ArrayList<String>();
    deliveryCollection()
        .find(expired)
        .limit(WORKFLOW_STARTS_SIEVED_PER_RUN)
        .forEach(document -> {
          if (Boolean.TRUE.equals(sieve.mayBeDeleted(recordOf(document)))) {
            mayGo.add(document.getString("_id"));
          }
        });
    if (mayGo.isEmpty()) {
      return 0;
    }
    return deliveryCollection()
        .deleteMany(new Document("_id", new Document("$in", mayGo)))
        .getDeletedCount();

  }

  /**
   * How long the document about a started workflow is kept, counted from the start. It is
   * <code>vanillabp.delivery.workflow-start-retention</code>, or thirty days where nobody says
   * anything.
   *
   * Public, because the tests of this wiring assert it.
   *
   * @return The period a workflow-start document is kept
   */
  public Duration getWorkflowStartRetention() {

    if (workflowStartRetention == null) {
      workflowStartRetention = io.vanillabp.integration.adapter.migration.config.DeliveryProperties
          .resolveWorkflowStartRetention(deliverySection());
    }
    return workflowStartRetention;

  }

  /**
   * What decides per document whether it may really go once that period has passed. It is
   * <code>null</code> where the application did not ask for the second sieve
   * (<code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>).
   *
   * @return The sieve or <code>null</code>
   */
  private io.vanillabp.integration.spi.WorkflowStartSieve getWorkflowStartSieve() {

    if ((workflowStartSieve == null) && io.vanillabp.integration.adapter.migration.config.DeliveryProperties
        .resolveKeepWorkflowStartWhileAggregateExists(deliverySection())) {
      workflowStartSieve = new io.vanillabp.integration.adapter.migration.delivery.AggregateBoundWorkflowStarts(
          () -> phaseTwoRouter.isResolvable()
              ? phaseTwoRouter.get()
              : null, deliveryCollectionName());
    }
    return workflowStartSieve;

  }

  /**
   * The <code>vanillabp.delivery</code> section of the whole application, as the core reads
   * it. The two settings about the documents of started workflows are read there and nowhere
   * else, like the retention next to them.
   *
   * @return The bound section
   */
  private io.vanillabp.integration.adapter.migration.config.DeliveryProperties deliverySection() {

    return QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(
            ConfigProvider
                .getConfig()
                .unwrap(SmallRyeConfig.class)
                .getConfigMapping(QuarkusMigrationAdapterProperties.class)
                .delivery());

  }


  /**
   * Tells the retention cleanup that this store was written to. So its next hourly run has
   * something to delete. It is null-safe. The cleanup is created when the store starts, and
   * a store which never started was never written to either.
   */
  private void aDeliveryWasRecorded() {

    final var cleanup = retentionCleanup;
    if (cleanup != null) {
      cleanup.aDeliveryWasRecorded();
    }

  }

  @Override
  public void stillOpen(
      final String deliveryKey) {

    aDeliveryWasRecorded();
    touches().remember(deliveryKey);

  }

  /**
   * Moves <code>lastSeenAt</code> of one block of records forward, in one round trip. A key
   * whose record was deleted in the meantime matches nothing. That is the right answer. The
   * record is gone, and the next repeated delivery writes a new one.
   *
   * @param deliveryKeys The keys of one block
   */
  private void refreshLastSeen(
      final List<String> deliveryKeys) {

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

    // Read through the session of the running transaction where there is one. So the
    // answer fits what this transaction wrote.
    final var session = MongoSessions
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
        .map(MongoTaskDeliveryLog::recordOf);

  }

  @Override
  public boolean record(
      final TaskDelivery delivery) {

    // This gives the hourly cleanup something to do. If nobody wrote to a store, an
    // earlier run already deleted everything there is to delete.
    aDeliveryWasRecorded();
    final var collection = deliveryCollection();
    // The session of the running transaction, where MongoDB Panache provides one. Then
    // the record commits with the aggregate and is not written at once.
    final var session = MongoSessions
        .activeSession(txRegistry);
    final var recordedAt = Date.from(delivery.recordedAt() == null
        ? Instant.now()
        : delivery.recordedAt());
    final var record = new Document()
        .append("_id", delivery.deliveryKey())
        // The delivering adapter as a field of its own. The delivery key has it too, but
        // only as a hash once the key grows too long.
        .append("adapterId", delivery.adapterId())
        .append("workflowModuleId", delivery.workflowModuleId())
        .append("bpmnProcessId", delivery.bpmnProcessId())
        .append("aggregateId", delivery.workflowAggregateId())
        // The workflow of the BPMS. VanillaBP does not read it. An operator uses it to find
        // this instance in the engine's own tools.
        .append("workflowId", delivery.workflowId())
        .append("taskDefinition", delivery.taskDefinition())
        // The element of the model. An extension uses it to address this task.
        .append("bpmnElementId", delivery.bpmnElementId())
        // The task which the delivery was about. With it, the election can answer from this
        // record which adapter holds that task. It does not have to ask every BPMS.
        .append("taskId", delivery.taskId())
        // Which kind of task this id belongs to. So VanillaBP can say which method asks for
        // that kind of key when a caller passed the id of the other kind.
        .append("taskKind", delivery.taskKind())
        // What the document is about: a task delivery or the start of a workflow.
        .append("recordKind", delivery.recordKind())
        .append("processVersion", delivery.processVersion())
        .append("outcome", delivery.outcome())
        .append("bpmnErrorCode", delivery.bpmnErrorCode())
        .append("bpmnErrorName", delivery.bpmnErrorName())
        .append("recordedAt", recordedAt)
        // The record was seen at the moment it was written. A repeated delivery of a task
        // which stays open moves lastSeenAt and leaves recordedAt where it is.
        .append("lastSeenAt", recordedAt);
    // A duplicate-key error inside a MongoDB transaction would abort the whole
    // transaction. So we read to find the usual duplicate. The unique document ID stays
    // the last line of defense.
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
      // 11000 = duplicate key: another node recorded the same delivery at the same time.
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

    // Without a session, MongoDB does not take part in the JTA transaction. So the record
    // is already written. Remove it again if the transaction does not commit. Otherwise a
    // repeated delivery of the rolled-back work would be skipped. With a session, the
    // abort of the MongoDB transaction removes the record too.
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

  /**
   * The record which left one task open, whether or not the task has been closed since. The
   * BPMS election of a task operation reads it instead of asking a BPMS (see
   * {@link TaskDeliveryLog#recordOfTask}). It is read through the session of the running
   * transaction where there is one. It is sorted, so the newest record answers where a task
   * was delivered more than once.
   */
  @Override
  public Optional<TaskDelivery> recordOfTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    final var session = MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("taskId", taskId)
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("outcome", COMPLETION_PENDING);
    final var newestFirst = new Document("recordedAt", -1);
    return Optional
        .ofNullable(
            session != null
                ? collection
                    .find(session, filter)
                    .sort(newestFirst)
                    .first()
                : collection
                    .find(filter)
                    .sort(newestFirst)
                    .first())
        .map(MongoTaskDeliveryLog::recordOf);

  }

  /**
   * The open tasks of one workflow aggregate (see
   * {@link TaskDeliveryLog#openTasksOfAggregate}), oldest first. These are records with the
   * outcome which left the task to the application, and without a moment which says that its
   * completion reached the BPMS. They are read through the session of the running transaction
   * where there is one. The index over <code>aggregateId</code>, which the startup creates,
   * serves the query.
   */
  @Override
  public List<TaskDelivery> openTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var session = MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("recordKind", new Document("$ne", WORKFLOW_START))
        .append("outcome", COMPLETION_PENDING)
        .append("taskClosedAt", null);
    final var oldestFirst = new Document("recordedAt", 1);
    final var records = new ArrayList<TaskDelivery>();
    (session != null
        ? collection.find(session, filter)
        : collection.find(filter))
        .sort(oldestFirst)
        .forEach(document -> records.add(recordOf(document)));
    return List.copyOf(records);

  }

  /**
   * The open tasks of one workflow of the BPMS (see
   * {@link TaskDeliveryLog#openTasksOfWorkflow}), oldest first. They are read through the
   * session of the running transaction where there is one. The index over
   * <code>workflowId</code>, which the startup creates, serves the query.
   */
  @Override
  public List<TaskDelivery> openTasksOfWorkflow(
      final String workflowModuleId,
      final String workflowId) {

    final var session = MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("workflowId", workflowId)
        .append("recordKind", new Document("$ne", WORKFLOW_START))
        .append("outcome", COMPLETION_PENDING)
        .append("taskClosedAt", null);
    final var oldestFirst = new Document("recordedAt", 1);
    final var records = new ArrayList<TaskDelivery>();
    (session != null
        ? collection.find(session, filter)
        : collection.find(filter))
        .sort(oldestFirst)
        .forEach(document -> records.add(recordOf(document)));
    return List.copyOf(records);

  }

  /**
   * Writes down that one task is over. The filter asks for a missing
   * <code>taskClosedAt</code>. So a repeated dispatch does not move the moment when the task
   * was closed.
   * <p>
   * <code>updateMany</code> and not <code>updateOne</code>: a task may have more than one
   * record. A record which is left open keeps the task alive for everything which reads the
   * open work (see {@link TaskDeliveryLog#markTaskClosed} and decision 72 in the repository's
   * DECISIONS.md).
   */
  @Override
  public int markTaskClosed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskId) {

    final var session = MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var filter = new Document("taskId", taskId)
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        .append("taskClosedAt", null);
    final var closeIt = Updates.set("taskClosedAt", new Date());
    final var result = session != null
        ? collection.updateMany(session, filter, closeIt)
        : collection.updateMany(filter, closeIt);
    return (int) result.getModifiedCount();

  }

  /**
   * Writes the document about the start of a workflow. It replaces the document of an earlier
   * workflow of the same aggregate (see {@link TaskDeliveryLog#recordWorkflowStart}).
   * <p>
   * The insert comes first, because usually there is no document. Only where it finds one
   * does the update run. The update is limited to a document which is a start and has a
   * different workflow id. So a start which is dispatched twice writes nothing. The version
   * moves with the id. The version of the workflow which ended says nothing about the one which
   * runs now. A document which already names this workflow may still get the version it is
   * missing, and nothing else. The updates go through the session of the running transaction
   * where MongoDB Panache provides one, like every other write here.
   *
   * @param workflowStart The row to write
   * @return Whether the store now holds this workflow's id or its version for the first time
   */
  @Override
  public boolean recordWorkflowStart(
      final TaskDelivery workflowStart) {

    if (record(workflowStart)) {
      return true;
    }
    final var startedAt = Date.from(workflowStart.recordedAt() == null
        ? Instant.now()
        : workflowStart.recordedAt());
    final var filter = new Document("_id", workflowStart.deliveryKey())
        .append("recordKind", WORKFLOW_START)
        .append("workflowId", new Document("$ne", workflowStart.workflowId()));
    final var replacement = new Document(
        "$set", new Document("workflowId", workflowStart.workflowId())
            .append("adapterId", workflowStart.adapterId())
            .append("recordedAt", startedAt)
            .append("lastSeenAt", startedAt)
            .append("processVersion", workflowStart.processVersion()));
    final var session = MongoSessions
        .activeSession(txRegistry);
    final var collection = deliveryCollection();
    final var result = session != null
        ? collection.updateOne(session, filter, replacement)
        : collection.updateOne(filter, replacement);
    if ((result.getModifiedCount() > 0) || (workflowStart.processVersion() == null)) {
      return result.getModifiedCount() > 0;
    }
    // The same workflow is reported a second time: by the BPMS where the adapter started
    // it, or the other way round. Only one of the two may have known the version.
    final var sameWorkflowWithoutAVersion = new Document("_id", workflowStart.deliveryKey())
        .append("recordKind", WORKFLOW_START)
        .append("workflowId", workflowStart.workflowId())
        .append("processVersion", null);
    final var addTheVersion = Updates.set("processVersion", workflowStart.processVersion());
    final var added = session != null
        ? collection.updateOne(session, sameWorkflowWithoutAVersion, addTheVersion)
        : collection.updateOne(sameWorkflowWithoutAVersion, addTheVersion);
    return added.getModifiedCount() > 0;

  }

  /**
   * The record one document holds.
   *
   * @param document The document read
   * @return What VanillaBP remembers about that delivery
   */
  private static TaskDelivery recordOf(
      final Document document) {

    return new TaskDelivery(document.getString("_id"), document.getString("adapterId"), document
        .getString("workflowModuleId"), document.getString("bpmnProcessId"), document
            .getString("aggregateId"), document.getString("workflowId"), document.getString("taskDefinition"), document
                .getString("bpmnElementId"), document
                    .getString("taskId"), document.getString("outcome"), document
                        .getString("bpmnErrorCode"), document.getString("bpmnErrorName"), instantOf(
                            document.getDate("recordedAt")), instantOf(
                                document.getDate("taskClosedAt")), document.getString("taskKind"), document
                                    .getString("recordKind"), document.getString("processVersion"));

  }

  /**
   * @param date A moment the document holds or <code>null</code>
   * @return The same moment, or <code>null</code>
   */
  private static Instant instantOf(
      final Date date) {

    return date == null
        ? null
        : date.toInstant();

  }

  /**
   * The adapter ids which the OPEN records of one BPMN process belong to. It is asked once per
   * BPMN process at startup.
   */
  @Override
  public Set<String> adapterIdsOfOpenTasks(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("recordKind", new Document("$ne", WORKFLOW_START))
        .append("outcome", COMPLETION_PENDING)
        .append("adapterId", new Document("$ne", null));
    final var adapterIds = new LinkedHashSet<String>();
    deliveryCollection()
        .distinct("adapterId", filter, String.class)
        .forEach(adapterIds::add);
    return adapterIds;

  }

  @Override
  public Boolean hasOpenRecords(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("recordKind", new Document("$ne", WORKFLOW_START))
        .append("outcome", COMPLETION_PENDING);
    return deliveryCollection().countDocuments(filter) > 0;

  }

  @Override
  public int releaseRecordsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final Instant recordedBefore) {

    final var collection = deliveryCollection();
    // Through the session of the running transaction, where MongoDB Panache provides one.
    // Then the deletion commits with the message about the end, and is not written at
    // once.
    final var session = MongoSessions
        .activeSession(txRegistry);
    final var filter = new Document()
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("aggregateId", workflowAggregateId)
        // The row about the start of that workflow is NOT released. Changes to the aggregate
        // keep arriving after its workflow ended, and each of them may want to name it.
        .append("recordKind", new Document("$ne", WORKFLOW_START))
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
        .getCollection(deliveryCollectionName());

  }

}
