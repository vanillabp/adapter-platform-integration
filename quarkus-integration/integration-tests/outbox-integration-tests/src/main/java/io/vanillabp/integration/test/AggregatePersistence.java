package io.vanillabp.integration.test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A simple in-memory aggregate persistence assigning generated IDs (like a JPA entity
 * with a generated ID would have).
 * <p>
 * It keeps a history as well: every save is a revision and a revision can be read back,
 * which is what an application using Hibernate Envers or a document store with versions
 * has without writing it. Only a test which asks for an old state notices.
 */
@ApplicationScoped
public class AggregatePersistence implements AggregatePersistenceAware<Aggregate> {

  private final Map<Long, Aggregate> aggregates = new ConcurrentHashMap<>();

  /**
   * What each revision of each aggregate looked like, addressed by
   * <code>&lt;id&gt;|&lt;revision&gt;</code>.
   */
  private final Map<String, Aggregate> revisions = new ConcurrentHashMap<>();

  /**
   * The revision each aggregate stands at right now.
   */
  private final Map<Long, String> currentRevision = new ConcurrentHashMap<>();

  private final AtomicLong idSequence = new AtomicLong(0);

  private final AtomicLong revisionSequence = new AtomicLong(0);

  @Override
  public Class<Aggregate> getAggregateClass() {

    return Aggregate.class;

  }

  @Override
  public Aggregate save(
      final Aggregate aggregate) {

    if (aggregate.getId() == null) {
      aggregate.setId(idSequence.incrementAndGet());
    }
    aggregates.put(aggregate.getId(), aggregate);
    final var revision = "rev-"
        + revisionSequence.incrementAndGet();
    revisions.put(keyOf(aggregate.getId(), revision), copyOf(aggregate));
    currentRevision.put(aggregate.getId(), revision);
    return aggregate;

  }

  @Override
  public Aggregate loadById(
      final Object aggregateId) {

    return aggregates.get(Long.valueOf(String.valueOf(aggregateId)));

  }

  /**
   * The revision this aggregate stands at - the state whoever asks is looking at.
   *
   * @return The revision, or <code>null</code> for an aggregate which was never saved
   */
  @Override
  public String getAuditingId(
      final Aggregate aggregate) {

    return currentRevision.get(aggregate.getId());

  }

  /**
   * The aggregate as that revision wrote it, as a copy: a state of a past moment is
   * something to read, not something to write back.
   */
  @Override
  public Aggregate loadByIdAndAuditingId(
      final Object aggregateId,
      final String auditingId) {

    final var asItWas = revisions.get(keyOf(Long.valueOf(String.valueOf(aggregateId)), auditingId));
    return asItWas == null
        ? null
        : copyOf(asItWas);

  }

  private static String keyOf(
      final Long aggregateId,
      final String auditingId) {

    return "%s|%s".formatted(aggregateId, auditingId);

  }

  private static Aggregate copyOf(
      final Aggregate aggregate) {

    final var copy = new Aggregate();
    copy.setId(aggregate.getId());
    copy.setContent(aggregate.getContent());
    return copy;

  }

  @Override
  public Object getAggregateId(
      final Aggregate aggregate) {

    return aggregate.getId();

  }

}
