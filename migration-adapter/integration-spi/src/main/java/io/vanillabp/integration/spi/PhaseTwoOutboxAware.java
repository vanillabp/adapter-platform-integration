package io.vanillabp.integration.spi;

/**
 * Selects the {@link PhaseTwoOutbox} used for aggregates of the given type.
 * <p>
 * The outbox entry MUST be enlisted in the same transaction which persists the
 * workflow aggregate (see the scheduling contract of {@link PhaseTwoOutbox}), so
 * which outbox to use is determined by the aggregate's persistence - PER AGGREGATE,
 * not per application: a JPA-persisted aggregate and a MongoDB-persisted aggregate in
 * the same application use different outboxes, each riding its own aggregate's
 * transaction. A dedicated outbox (on its own store) may also be assigned to isolate
 * a high-load process from the others.
 * <p>
 * Implementations are CDI/Spring beans. The implementation with the most specific
 * aggregate class is chosen, taking superclasses and implemented interfaces into
 * account - the same selection semantics as
 * {@link AggregatePersistenceAware}. Without any
 * matching implementation the platform integration falls back to its default
 * selection (the outbox matching the persistence technology managing the aggregate,
 * or the single available outbox).
 * <p>
 * Every {@link PhaseTwoOutbox} instance needs its own store (table/collection): two
 * dispatchers polling the same store would compete and double-dispatch. The
 * platform-provided outbox implementations accept a store name for this purpose (see
 * <code>vanillabp.outbox.*</code>).
 * <p>
 * All methods are <code>default</code> methods throwing an
 * {@link UnsupportedOperationException} with a guiding message: this keeps
 * hand-written implementations source-compatible when methods are added to this
 * interface.
 *
 * @param <A> The aggregate type
 */
public interface PhaseTwoOutboxAware<A> {

  /**
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
   * aggregates, and MUST use its own store (table/collection) not shared with any
   * other outbox instance.
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
