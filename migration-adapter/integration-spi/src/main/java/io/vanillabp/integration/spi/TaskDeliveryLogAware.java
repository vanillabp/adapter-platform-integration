package io.vanillabp.integration.spi;

/**
 * Selects the {@link TaskDeliveryLog} used for aggregates of the given type.
 * <p>
 * A delivery record MUST be enlisted in the same transaction which persists the
 * workflow aggregate (see the recording contract of {@link TaskDeliveryLog}), so which
 * log to use is determined by the aggregate's persistence - PER AGGREGATE, not per
 * application: a JPA-persisted aggregate and a MongoDB-persisted aggregate in the same
 * application use different logs, each riding its own aggregate's transaction. This
 * mirrors {@link PhaseTwoOutboxAware}, and an application providing its own store for
 * one of the two directions usually provides both.
 * <p>
 * Implementations are CDI/Spring beans. The implementation with the most specific
 * aggregate class is chosen, taking superclasses and implemented interfaces into
 * account - the same selection semantics as {@link AggregatePersistenceAware}. Without
 * any matching implementation the platform integration falls back to its default
 * selection (the log matching the persistence technology managing the aggregate, or
 * the single available log).
 * <p>
 * All methods are <code>default</code> methods throwing an
 * {@link UnsupportedOperationException} with a guiding message: this keeps
 * hand-written implementations source-compatible when methods are added to this
 * interface.
 *
 * @param <A> The aggregate type
 */
public interface TaskDeliveryLogAware<A> {

  /**
   * @return The aggregate class this delivery-log selection applies to.
   */
  default Class<A> getAggregateClass() {

    throw new UnsupportedOperationException(
        """
            getAggregateClass is not implemented by '%s'! VanillaBP uses it to select the \
            TaskDeliveryLogAware implementation responsible for a workflow aggregate - implement \
            getAggregateClass in your TaskDeliveryLogAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * The delivery log used for aggregates of {@link #getAggregateClass()}. The
   * returned log MUST enlist records in the same transaction which persists these
   * aggregates.
   *
   * @return The delivery log to use
   */
  default TaskDeliveryLog getTaskDeliveryLog() {

    throw new UnsupportedOperationException(
        """
            getTaskDeliveryLog is not implemented by '%s'! VanillaBP uses it to determine the task \
            delivery log for workflow aggregates of the class returned by getAggregateClass - \
            implement getTaskDeliveryLog in your TaskDeliveryLogAware implementation."""
            .formatted(getClass().getName()));

  }

}
