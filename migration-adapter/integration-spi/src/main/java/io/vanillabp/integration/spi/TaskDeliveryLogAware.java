package io.vanillabp.integration.spi;

/**
 * Selects the {@link TaskDeliveryLog} used for aggregates of the given type.
 * <p>
 * A delivery record MUST be enlisted in the same transaction which persists the
 * workflow aggregate (see the recording contract of {@link TaskDeliveryLog}). So the
 * persistence of the aggregate decides which log to use. This is decided PER AGGREGATE,
 * not per application. An aggregate persisted with JPA and an aggregate persisted with
 * MongoDB in the same application use different logs. Each one uses the transaction of
 * its own aggregate. This works like {@link PhaseTwoOutboxAware}. An application which
 * provides its own store for one of the two directions usually provides both.
 * <p>
 * Implementations are CDI/Spring beans. The implementation with the most specific
 * aggregate class is chosen, and superclasses and implemented interfaces are taken into
 * account. The selection works like for {@link AggregatePersistenceAware}. If no
 * implementation matches, the platform integration falls back to its default selection
 * (the log which matches the persistence technology of the aggregate, or the only log
 * available).
 * <p>
 * All methods are <code>default</code> methods. They throw an
 * {@link UnsupportedOperationException} with a message which guides the developer. So
 * implementations written by hand still compile when methods are added to this
 * interface.
 *
 * @param <A> The aggregate type
 */
public interface TaskDeliveryLogAware<A> {

  /**
   * Which aggregates this selection is about. VanillaBP picks the implementation whose
   * class fits an aggregate most closely. So the answer decides whose deliveries are
   * written into the log below.
   *
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
