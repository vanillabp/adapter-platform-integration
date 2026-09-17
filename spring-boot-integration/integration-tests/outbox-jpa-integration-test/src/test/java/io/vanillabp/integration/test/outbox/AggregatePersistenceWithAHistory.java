package io.vanillabp.integration.test.outbox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The persistence of this scenario's workflow aggregate, written by the application
 * instead of being left to Spring Data alone, because this application keeps a history
 * of its aggregate: every save gets a revision, and a revision can be read back.
 * <p>
 * An application using Hibernate Envers has the same two answers without writing them
 * itself; this one keeps the old states in a map, which is all a test needs and all a
 * document store would do by hand. Everything else is the repository's work, exactly as
 * before.
 */
public class AggregatePersistenceWithAHistory implements AggregatePersistenceAware<Aggregate> {

  /**
   * One state of one aggregate, as it was written.
   */
  private record Revision(
                          String content,
                          String reported) {
  }

  private final AggregateRepository repository;

  private final Map<String, Revision> revisions = new ConcurrentHashMap<>();

  /**
   * The revision each aggregate stands at, which is what {@link #getAuditingId(Aggregate)}
   * answers.
   */
  private final Map<Object, String> currentRevision = new ConcurrentHashMap<>();

  private final AtomicInteger nextRevision = new AtomicInteger(1);

  public AggregatePersistenceWithAHistory(
      final AggregateRepository repository) {

    this.repository = repository;

  }

  @Override
  public Class<Aggregate> getAggregateClass() {

    return Aggregate.class;

  }

  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final Aggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public Aggregate save(
      final Aggregate aggregate) {

    final var saved = repository.save(aggregate);
    final var revision = "rev-"
        + nextRevision.getAndIncrement();
    revisions.put(keyOf(saved.getId(), revision), new Revision(saved.getContent(), saved.getReported()));
    currentRevision.put(String.valueOf(saved.getId()), revision);
    return saved;

  }

  @Override
  public Aggregate loadById(
      final Object aggregateId) {

    return repository
        .findById(Long.valueOf(String.valueOf(aggregateId)))
        .orElse(null);

  }

  /**
   * The revision this aggregate stands at, which is the state whoever asks is looking at.
   *
   * @return The revision, or <code>null</code> for an aggregate which was never saved
   */
  @Override
  public String getAuditingId(
      final Aggregate aggregate) {

    return currentRevision.get(String.valueOf(aggregate.getId()));

  }

  /**
   * The aggregate as that revision wrote it, DETACHED - the state of a past moment is
   * something to read, not something to write back, and an application loading from
   * Envers gets a detached object as well.
   */
  @Override
  public Aggregate loadByIdAndAuditingId(
      final Object aggregateId,
      final String auditingId) {

    final var revision = revisions.get(keyOf(aggregateId, auditingId));
    if (revision == null) {
      return null;
    }
    final var asItWas = new Aggregate();
    asItWas.setId(Long.valueOf(String.valueOf(aggregateId)));
    asItWas.setContent(revision.content());
    asItWas.setReported(revision.reported());
    return asItWas;

  }

  /**
   * Forgets every state written before the given revision - what the clean-up of an
   * auditing does while an outbox entry is still waiting for its dispatch.
   *
   * @param revision The oldest revision which is kept
   */
  public void cleanUpEverythingBefore(
      final int revision) {

    revisions
        .keySet()
        .removeIf(key -> Integer.parseInt(key.substring(key.indexOf("|rev-") + 5)) < revision);

  }

  private static String keyOf(
      final Object aggregateId,
      final String auditingId) {

    return "%s|%s".formatted(aggregateId, auditingId);

  }

}
