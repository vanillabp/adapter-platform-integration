package io.vanillabp.integration.spi;

/**
 * Implemented by classes which are aware of persisting aggregates of the given type.
 * <p>
 * This interface may be implemented by the platform-specific adapter to provide generic aggregate persistence.
 * However, if the platform does not support this or an aggregate has to be persisted differently, then
 * the business processing application may provide additional implementations (e.g., by the service annotated
 * by the @{@link io.vanillabp.spi.service.WorkflowService} annotation). The implementation with the most specific
 * generic parameter is chosen, also taking superclasses and implemented interfaces into account.
 * <p>
 * This is the single, platform-independent interface: business code implements it regardless
 * of whether the application runs on Spring Boot or Quarkus.
 * <p>
 * All methods are <code>default</code> methods throwing an
 * {@link UnsupportedOperationException} with a guiding message: this keeps
 * hand-written implementations source-compatible when methods are added to this
 * interface. The platform-provided implementations (e.g. based on Spring Data)
 * override all of them; custom implementations have to override every method
 * VanillaBP actually uses for their aggregates.
 *
 * @param <A> The aggregate type
 */
public interface AggregatePersistenceAware<A> {

  /**
   * @return The aggregate class.
   */
  default Class<A> getAggregateClass() {

    throw new UnsupportedOperationException(
        """
            getAggregateClass is not implemented by '%s'! VanillaBP uses it to select the \
            implementation responsible for a workflow aggregate - implement getAggregateClass in \
            your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Persists the given aggregate.
   *
   * @param aggregate The aggregate to persist
   * @return The persisted aggregate, in case of ORM frameworks an attached object is returned.
   */
  default A save(
      final A aggregate) {

    throw new UnsupportedOperationException(
        """
            save is not implemented by '%s'! VanillaBP needs to persist the workflow aggregate \
            (e.g. when starting a workflow or after processing a BPMN task) - implement save in \
            your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * @param aggregate The aggregate to investigate
   * @return The aggregate's ID.
   */
  default Object getAggregateId(
      final A aggregate) {

    throw new UnsupportedOperationException(
        """
            getAggregateId is not implemented by '%s'! VanillaBP needs the workflow aggregate's ID \
            (e.g. as the workflow's business key) - implement getAggregateId in your \
            AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Loads the aggregate by its ID. Used by VanillaBP e.g. when processing BPMN
   * tasks (the aggregate is loaded, the business method is executed and the
   * aggregate is saved within one transaction).
   * <p>
   * The platform-provided implementations (e.g. based on Spring Data) support this
   * out of the box; custom implementations have to override this method.
   *
   * @param aggregateId The aggregate's ID (as returned by {@link #getAggregateId(Object)})
   * @return The aggregate or <code>null</code> if there is none having the given ID
   */
  default A loadById(
      final Object aggregateId) {

    throw new UnsupportedOperationException(
        """
            loadById is not implemented by '%s'! VanillaBP needs to load the workflow aggregate by \
            its ID (e.g. when processing BPMN tasks) - implement loadById in your \
            AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

}
