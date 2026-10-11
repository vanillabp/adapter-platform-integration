package io.vanillabp.integration.spi;

/**
 * Selects the {@link PhaseTwoOutbox} used for aggregates of the given type.
 * <p>
 * The outbox entry MUST be enlisted in the same transaction which persists the
 * workflow aggregate (see the scheduling contract of {@link PhaseTwoOutbox}). So the
 * persistence of the aggregate decides which outbox to use. This is decided PER
 * AGGREGATE, not per application. An aggregate persisted with JPA and an aggregate
 * persisted with MongoDB in the same application use different outboxes. Each one uses
 * the transaction of its own aggregate. You may also assign an outbox of its own (on its
 * own store), to keep a process with a high load apart from the others.
 * <p>
 * Implementations are CDI/Spring beans. The implementation with the most specific
 * aggregate class is chosen, and superclasses and implemented interfaces are taken into
 * account. The selection works like for {@link AggregatePersistenceAware}. If no
 * implementation matches, the platform integration falls back to its default selection
 * (the outbox which matches the persistence technology of the aggregate, or the only
 * outbox available).
 * <p>
 * Every {@link PhaseTwoOutbox} instance needs its own store (table/collection). Two
 * dispatchers which poll the same store would compete and dispatch twice. The outbox
 * implementations of the platform accept a store name for this (see
 * <code>vanillabp.outbox.*</code>).
 * <p>
 * All methods are <code>default</code> methods. They throw an
 * {@link UnsupportedOperationException} with a message which guides the developer. So
 * implementations written by hand still compile when methods are added to this
 * interface.
 *
 * @param <A> The aggregate type
 */
public interface PhaseTwoOutboxAware<A> {

  /**
   * Which aggregates this selection is about. VanillaBP picks the implementation whose
   * class fits an aggregate most closely. So the answer decides whose entries go into the
   * outbox below.
   *
   * @return The aggregate class this outbox selection applies to.
   */
  default Class<A> getAggregateClass() {

    throw new UnsupportedOperationException(
        """
            getAggregateClass is not implemented by '%s'! VanillaBP uses it to select the \
            PhaseTwoOutboxAware implementation responsible for a workflow aggregate - implement \
            getAggregateClass in your PhaseTwoOutboxAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * The outbox used for aggregates of {@link #getAggregateClass()}. The returned
   * outbox MUST enlist entries in the same transaction which persists these
   * aggregates. It MUST use its own store (table/collection), which no other outbox
   * instance shares.
   *
   * @return The outbox to use
   */
  default PhaseTwoOutbox getPhaseTwoOutbox() {

    throw new UnsupportedOperationException(
        """
            getPhaseTwoOutbox is not implemented by '%s'! VanillaBP uses it to determine the \
            phase-two outbox for workflow aggregates of the class returned by getAggregateClass - \
            implement getPhaseTwoOutbox in your PhaseTwoOutboxAware implementation."""
            .formatted(getClass().getName()));

  }

}
