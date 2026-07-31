package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two
 * outbox (see {@link MongoPhaseTwoOutbox}) through the core's
 * {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 * <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 * by <code>vanillabp.outbox.poll-interval</code>) started on
 * {@link StartupEvent}.</li>
 * </ul>
 * Due entries (status {@link MongoPhaseTwoOutbox#STATUS_OPEN}) are claimed
 * atomically (<code>findOneAndUpdate</code> incrementing the number of attempts and
 * setting the next attempt according to
 * <code>vanillabp.outbox.attempt-frequency</code>), so a failed dispatch is retried
 * with a backoff and multiple application instances (pods) may poll concurrently
 * without any distributed lock - exactly one instance wins each claim. On
 * successful dispatch the entry is marked {@link MongoPhaseTwoOutbox#STATUS_DONE}
 * (kept until <code>vanillabp.outbox.retention</code> passed - the deduplication
 * window of the idempotency contract); after
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts it is marked
 * {@link MongoPhaseTwoOutbox#STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is disabled, a partial unique
 * index on the entries' idempotency key is created on startup (partial: entries
 * without a key - operations which must not be deduplicated - are not indexed). If
 * the schema is managed manually, create that index yourself.
 * <p>
 * The database is taken from <code>quarkus.mongodb.database</code> - the same
 * database the application's aggregates live in.
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutboxDispatcher {

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  private volatile PhaseTwoOutboxProperties properties;

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily so
   * {@link MongoPhaseTwoOutbox} can resolve its collection even before the startup
   * event was observed.
   *
   * @return The outbox configuration
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

  private ScheduledExecutorService executor;

  /**
   * Creates the unique index (unless disabled) and starts the fixed-delay poller.
   * The first run is executed immediately, dispatching committed-but-unprocessed
   * entries of a previously crashed instance.
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes final StartupEvent event) {

    if (!mongoClient.isResolvable()) {
      log.debug("No MongoDB client available - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    getProperties();
    if (!properties.getMongo().isEnabled()) {
      log.debug("'vanillabp.outbox.mongo.enabled' is false - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    if (properties.isCreateSchema()) {
      outboxCollection().createIndex(
          Indexes.ascending("idempotencyKey"),
          new IndexOptions()
              .unique(true)
              .partialFilterExpression(Filters.exists("idempotencyKey", true)));
    }

    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, "vanillabp-outbox");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(
        this::poll,
        0,
        properties.getPollInterval().toMillis(),
        TimeUnit.MILLISECONDS);

  }

  @PreDestroy
  void shutdown() {

    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }

  }

  /**
   * Runs a single poll asynchronously (used right after a commit).
   */
  public void triggerPoll() {

    if (executor != null) {
      executor.execute(this::poll);
    }

  }

  /**
   * The collection storing the outbox entries, resolved from the database
   * configured by <code>quarkus.mongodb.database</code>.
   *
   * @return The outbox collection
   */
  MongoCollection<Document> outboxCollection() {

    final var database = ConfigProvider
        .getConfig()
        .getOptionalValue("quarkus.mongodb.database", String.class)
        .orElseThrow(() -> new IllegalStateException(
            """
                The MongoDB-based phase-two outbox needs the database name! Set the property \
                'quarkus.mongodb.database' (the same database the workflow aggregates live in)."""));
    return mongoClient
        .get()
        .getDatabase(database)
        .getCollection(getProperties()
            .getMongo()
            .getCollection());

  }

  /**
   * Claims and dispatches all due entries, then deletes DONE entries whose
   * retention passed. Exceptions are caught to keep the poller alive.
   */
  private synchronized void poll() {

    try {
      final var collection = outboxCollection();
      while (true) {
        final var now = Instant.now();
        // claim atomically: increment attempts and set the backoff, so other
        // instances skip the entry and a failed dispatch is retried automatically
        final var entry = collection.findOneAndUpdate(
            Filters.and(
                Filters.eq("status", MongoPhaseTwoOutbox.STATUS_OPEN),
                Filters.lte("nextAttemptAt", Date.from(now)),
                Filters.lt("attempts", properties.getBlockAfterAttempts())),
            Updates.combine(
                Updates.inc("attempts", 1),
                Updates.set("nextAttemptAt", Date.from(now.plus(properties.getAttemptFrequency())))));
        if (entry == null) {
          break;
        }
        dispatch(collection, entry);
      }
      // asynchronous retention cleanup of the "DONE instead of delete" contract
      collection.deleteMany(
          Filters.and(
              Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
              Filters.lt("doneAt", Date.from(Instant.now().minus(properties.getRetention())))));
    } catch (final RuntimeException e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * @param collection The outbox collection
   * @param entry The claimed entry (holding the state before it was claimed)
   */
  private void dispatch(
      final MongoCollection<Document> collection,
      final Document entry) {

    final var entryId = entry.getString("_id");
    try {
      final PhaseTwoOperation operation;
      try {
        operation = PhaseTwoOperation.valueOf(entry.getString("operation"));
      } catch (final IllegalArgumentException e) {
        throw new IllegalStateException(
            "Unknown operation '%s' of outbox entry '%s'! Maybe it was written by a newer version of your software?"
                .formatted(entry.getString("operation"), entryId));
      }
      final var argsDocument = entry.get("args", Document.class);
      final Map<String, String> args = new LinkedHashMap<>();
      if (argsDocument != null) {
        argsDocument.forEach((
            key,
            value) -> args.put(key, String.valueOf(value)));
      }
      phaseTwoRouter
          .get()
          .dispatch(new PhaseTwoCall(
              operation, entry.getString("workflowModuleId"), entry.getString("bpmnProcessId"), entry
                  .getString("aggregateId"), entry.getString("adapterId"), args));
      collection.updateOne(
          Filters.eq("_id", entryId),
          Updates.combine(
              Updates.set("status", MongoPhaseTwoOutbox.STATUS_DONE),
              Updates.set("doneAt", Date.from(Instant.now()))));
    } catch (final RuntimeException e) {
      if (entry.getInteger("attempts") + 1 >= properties.getBlockAfterAttempts()) {
        collection.updateOne(
            Filters.eq("_id", entryId),
            Updates.set("status", MongoPhaseTwoOutbox.STATUS_BLOCKED));
        log.error(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entry.getInteger("attempts") + 1,
            entryId,
            e);
      } else {
        log.warn(
            "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed - will retry",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            e);
      }
    }

  }

}
