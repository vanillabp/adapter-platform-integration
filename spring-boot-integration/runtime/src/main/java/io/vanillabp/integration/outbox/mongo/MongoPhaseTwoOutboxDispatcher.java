package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.TaskScheduler;

import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;
import io.vanillabp.integration.outbox.AggregateIdConverter;
import io.vanillabp.integration.outbox.PhaseTwoOutboxProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two outbox
 * to the {@link MigratableProcessServicePhaseTwo} bean:
 * <ul>
 *   <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 *   <li>by a fixed-delay poller (crash recovery and retries, poll interval configured
 *       by <code>vanillabp.outbox.poll-interval</code>) started on
 *       {@link ApplicationReadyEvent}.</li>
 * </ul>
 * Due entries are claimed atomically (find-and-modify incrementing the number of
 * attempts and setting the next attempt according to
 * <code>vanillabp.outbox.attempt-frequency</code>), so a failed dispatch is
 * automatically retried with a backoff and multiple instances do not dispatch the same
 * entry concurrently. Entries are removed on successful dispatch only (at-least-once
 * semantics). After <code>vanillabp.outbox.block-after-attempts</code> failed attempts
 * an entry is blocked and has to be cleaned up manually.
 */
@RequiredArgsConstructor
@Slf4j
public class MongoPhaseTwoOutboxDispatcher {

  private final MongoTemplate mongoTemplate;

  private final ObjectProvider<MigratableProcessServicePhaseTwo> processServicePhaseTwo;

  private final TaskScheduler taskScheduler;

  private final PhaseTwoOutboxProperties properties;

  private ScheduledFuture<?> poller;

  /**
   * Starts the fixed-delay poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller = taskScheduler.scheduleWithFixedDelay(
        this::poll,
        properties.getPollInterval());

  }

  @PreDestroy
  public void stopPolling() {

    if (poller != null) {
      poller.cancel(false);
      poller = null;
    }

  }

  /**
   * Runs a single poll asynchronously (used right after a commit).
   */
  public void triggerPoll() {

    taskScheduler.schedule(this::poll, Instant.now());

  }

  /**
   * Claims and dispatches all due entries. Exceptions are caught to keep the poller
   * alive.
   */
  private void poll() {

    try {
      while (true) {
        final var now = Instant.now();
        final var due = Query.query(Criteria
            .where("nextAttemptAt")
            .lte(now)
            .and("attempts")
            .lt(properties.getBlockAfterAttempts()));
        // claim the entry atomically: increment attempts and set the backoff, so
        // other instances skip it and a failed dispatch is retried automatically
        final var claim = new Update()
            .inc("attempts", 1)
            .set("nextAttemptAt", now.plus(properties.getAttemptFrequency()));
        final var entry = mongoTemplate.findAndModify(
            due, claim, PhaseTwoOutboxEntry.class, MongoPhaseTwoOutbox.COLLECTION);
        if (entry == null) {
          break;
        }
        dispatch(entry);
      }
    } catch (Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Dispatches a single claimed entry. On success the entry is removed; on failure it
   * stays claimed and is retried after the configured backoff.
   *
   * @param entry The claimed entry (holding the state before it was claimed)
   */
  private void dispatch(
      final PhaseTwoOutboxEntry entry) {

    try {
      processServicePhaseTwo
          .getObject()
          .startWorkflowPhaseTwo(
              entry.getWorkflowModuleId(),
              entry.getBpmnProcessId(),
              entry.getAdapterId(),
              convertAggregateId(entry));
      mongoTemplate.remove(
          Query.query(Criteria.where("_id").is(entry.getId())),
          MongoPhaseTwoOutbox.COLLECTION);
    } catch (Exception e) {
      if (entry.getAttempts() + 1 >= properties.getBlockAfterAttempts()) {
        log.error(
            "Starting workflow (phase two) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getAttempts() + 1,
            entry.getId(),
            e);
      } else {
        log.warn(
            "Starting workflow (phase two) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
                + "failed - will retry",
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            e);
      }
    }

  }

  /**
   * Converts the serialized aggregate ID back to its original type stored along the
   * entry. Unknown types are passed through as strings.
   *
   * @param entry The entry to be dispatched
   * @return The aggregate ID to be passed on
   */
  private Object convertAggregateId(
      final PhaseTwoOutboxEntry entry) {

    if (entry.getAggregateId() == null || entry.getAggregateIdType() == null) {
      return entry.getAggregateId();
    }
    final Class<?> aggregateIdType;
    try {
      aggregateIdType = Class.forName(entry.getAggregateIdType());
    } catch (ClassNotFoundException e) {
      log.warn(
          "Unknown workflow-aggregate ID type '{}' of outbox entry '{}' - passing the ID through as a string!",
          entry.getAggregateIdType(),
          entry.getId());
      return entry.getAggregateId();
    }
    return AggregateIdConverter.convert(entry.getAggregateId(), aggregateIdType);

  }

}
