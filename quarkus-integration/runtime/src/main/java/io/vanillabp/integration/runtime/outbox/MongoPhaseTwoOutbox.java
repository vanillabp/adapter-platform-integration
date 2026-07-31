package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.UUID;

import org.bson.Document;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;

import io.vanillabp.integration.adapter.spi.PhaseTwoCall;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Quarkus applications using
 * MongoDB (extension <code>quarkus-mongodb-client</code>) for aggregate persistence.
 * The entry persists the fields of the {@link PhaseTwoCall} including the operation
 * discriminator and the elected adapter ID; deduplication is enforced by a partial
 * unique index on the entry's idempotency key (a duplicate schedule is the
 * contract's no-op). The collection name matches the Spring Boot MongoDB outbox
 * (<code>vanillabp.outbox.mongo.collection</code>, default
 * <code>vanillabp-phase-two-outbox</code>) so both platforms share the same store
 * layout.
 * <p>
 * <strong>Best-effort window (no MongoDB transaction):</strong> MongoDB is no JTA
 * resource and the Quarkus MongoDB client does not enlist in the Narayana
 * transaction, so - exactly like the Spring Boot MongoDB outbox without a replica
 * set - the entry is written <i>immediately</i>, before the local commit. Two
 * windows follow:
 * <ul>
 * <li><i>Rollback:</i> the already-written entry would become an orphan and the
 * poller would start a workflow whose aggregate does not exist. Mitigation: on
 * {@link Status#STATUS_ROLLEDBACK} the entry is deleted best-effort; only a crash
 * between the insert and the rollback handling leaves an orphan behind (visible in
 * the outbox store as a repeatedly failing, finally BLOCKED entry).</li>
 * <li><i>Crash before commit:</i> the entry exists but the aggregate was never
 * committed - same as above, the dispatch fails repeatedly and the entry is
 * blocked with a monitorable ERROR.</li>
 * </ul>
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutbox implements PhaseTwoOutbox {

  public static final String STATUS_OPEN = "OPEN";

  public static final String STATUS_DONE = "DONE";

  public static final String STATUS_BLOCKED = "BLOCKED";

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  MongoPhaseTwoOutboxDispatcher dispatcher;

  /**
   * Whether this default outbox is usable: the extension registers the bean at
   * build time, but without a MongoDB client (and database) it cannot store
   * anything - an unusable default must not be selected for an aggregate (the
   * startup validation then reports "no outbox available" with the remedies
   * instead of failing at the first workflow start).
   *
   * @return Whether a MongoDB client is available
   */
  public boolean isAvailable() {

    return mongoClient.isResolvable();

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var collection = dispatcher.outboxCollection();

    // pre-check for an existing entry (fast no-op for the common duplicate case);
    // the partial unique index remains the backstop for concurrent duplicates
    final var idempotencyKey = call.idempotencyKey().orElse(null);
    if ((idempotencyKey != null) && (collection.find(new Document("idempotencyKey", idempotencyKey)).first() != null)) {
      logDuplicate(call);
      return false;
    }

    final var now = Instant.now();
    final var entryId = UUID.randomUUID().toString();
    final var entry = new Document()
        .append("_id", entryId)
        .append("workflowModuleId", call.workflowModuleId())
        .append("bpmnProcessId", call.bpmnProcessId())
        .append("operation", call.operation().name())
        .append("aggregateId", call.workflowAggregateId())
        .append("adapterId", call.adapterId())
        .append("args", new Document(new java.util.LinkedHashMap<String, Object>(call.args())))
        .append("idempotencyKey", idempotencyKey)
        .append("status", STATUS_OPEN)
        .append("createdAt", java.util.Date.from(now))
        .append("attempts", 0)
        .append("nextAttemptAt", java.util.Date.from(now));
    try {
      collection.insertOne(entry);
    } catch (final MongoWriteException e) {
      // 11000 = duplicate key: the idempotency key is already present
      if (e.getError().getCode() == 11000) {
        logDuplicate(call);
        return false;
      }
      throw e;
    }

    if (txRegistry.getTransactionKey() != null) {
      // dispatch right after the commit; on rollback delete the entry best-effort
      // (see the class javadoc for the remaining crash windows)
      txRegistry.registerInterposedSynchronization(new Synchronization() {
        @Override
        public void beforeCompletion() {
          // nothing to do
        }

        @Override
        public void afterCompletion(
            final int status) {
          if (status == Status.STATUS_COMMITTED) {
            dispatcher.triggerPoll();
          } else {
            try {
              collection.deleteOne(new Document("_id", entryId));
            } catch (final RuntimeException e) {
              log.warn(
                  "Could not delete the phase-two outbox entry '{}' after the rollback of the local "
                      + "transaction - the entry is an orphan and will end up BLOCKED after failing "
                      + "dispatches; clean it up manually",
                  entryId,
                  e);
            }
          }
        }
      });
    } else {
      dispatcher.triggerPoll();
    }

    return true;

  }

  private static void logDuplicate(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "was already scheduled - skipping",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

}
