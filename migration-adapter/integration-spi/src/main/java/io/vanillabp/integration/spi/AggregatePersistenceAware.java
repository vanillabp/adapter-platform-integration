package io.vanillabp.integration.spi;

/**
 * Implemented by classes which can persist aggregates of the given type.
 * <p>
 * The platform-specific adapter may implement this interface to provide generic aggregate
 * persistence. If the platform does not support this, or if an aggregate has to be persisted
 * in another way, the business processing application may provide more implementations (e.g.,
 * the service annotated with @{@link io.vanillabp.spi.service.WorkflowService}). VanillaBP
 * chooses the implementation with the most specific generic parameter. It also takes
 * superclasses and implemented interfaces into account.
 * <p>
 * This is the single interface for all platforms. Business code implements it, whether
 * the application runs on Spring Boot or Quarkus.
 * <p>
 * <b>What VanillaBP guarantees around the calls.</b> Loading the aggregate, invoking the
 * <code>&#64;WorkflowTask</code> method, saving the aggregate, remembering the delivery and
 * scheduling a phase-two outbox entry happen inside ONE transaction. VanillaBP opens it on
 * the threads it owns (an adapter thread which delivers a task, the thread of the outbox
 * dispatcher). So no implementation needs a transaction annotation for VanillaBP.
 * <p>
 * Which transaction that is depends on the store. It is the transaction of the platform if
 * the platform manages the persistence (JPA/JDBC, and MongoDB Panache on Quarkus, which binds
 * a MongoDB transaction to the JTA one). It is the transaction of the application if the
 * platform does not. So an implementation which writes into a system the platform does not
 * manage (an event store, a ledger, a message producer, a service behind an API) comes with a
 * {@link TransactionRunner}. It is contributed as a bean, or assigned per aggregate through
 * {@link TransactionRunnerAware}. Without one, VanillaBP cannot put the three calls it makes
 * into one bracket. It says so at startup where it can tell (see the wiki page "Workflow
 * aggregates" for what is left of the guarantees then).
 * <p>
 * Every method is a <code>default</code> method. So implementations written by hand still
 * compile when methods are added to this interface. Most of them throw an
 * {@link UnsupportedOperationException} with a message which guides the developer. The
 * implementations of the platform (e.g. based on Spring Data) override those. A custom
 * implementation overrides every method VanillaBP really uses for its aggregates. The
 * methods which answer instead of throwing say so where they are declared. Each of them
 * answers what an application which does not implement it would want.
 *
 * @param <A> The aggregate type
 */
public interface AggregatePersistenceAware<A> {

  /**
   * The aggregate class this implementation persists. VanillaBP picks the implementation
   * whose class fits a workflow aggregate most closely. So this answer decides which
   * aggregates reach it.
   *
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
   * <p>
   * VanillaBP calls this after every <code>&#64;WorkflowTask</code> method, also when the
   * method did not change the aggregate. An implementation may leave out the write of an
   * aggregate which did not change since {@link #loadById(Object)} loaded it in the same
   * transaction, and return the loaded object. The MongoDB implementations of the platform do
   * that, because MongoDB writes the whole document on every save. JPA checks this by itself.
   * See {@code DECISIONS.pending/989.md} for why the check lives here and not in the core.
   *
   * @param aggregate The aggregate to persist
   * @return The persisted aggregate. With ORM frameworks, an attached object is returned.
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
   * Reads the ID out of an aggregate. It names the workflow in everything VanillaBP writes
   * down about it: the outbox entry, the delivery record and, depending on the adapter,
   * the BPMS itself.
   *
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
   * Determines the name of the ID property of the aggregate. The adapter decides how the ID
   * of the aggregate is stored in the BPMS. Adapters with their own concept of a business
   * identifier (e.g. the Camunda 7 business key) do not need this name. Adapters which store
   * the aggregate (or its ID) in the BPMS itself (e.g. Camunda 8) name the process variable
   * after the ID property of the aggregate.
   * <p>
   * The implementations of the platform (e.g. based on Spring Data) support this out of
   * the box. Custom implementations have to override this method if such an adapter is
   * used.
   *
   * @return The name of the aggregate's ID property
   */
  default String getAggregateIdName() {

    throw new UnsupportedOperationException(
        """
            getAggregateIdName is not implemented by '%s'! The configured VanillaBP adapter stores \
            the workflow aggregate's ID in the BPMS named like the aggregate's ID property - \
            implement getAggregateIdName in your AggregatePersistenceAware implementation."""
            .formatted(getClass().getName()));

  }

  /**
   * Determines the type of the ID property of the aggregate. VanillaBP uses it at startup
   * to check that the ID survives the String serialization of the phase-two outbox without
   * loss. At outbox dispatch it uses it to convert the serialized ID back to this type.
   * <p>
   * <b>This default does NOT throw</b> (unlike the other defaults). A <code>null</code>
   * return means that the ID type cannot be determined. Then the custom persistence layer
   * owns the serialized form: the ID is passed through as a String, and nothing is
   * checked. The default determines the type by reflection (see {@link AggregateIdTypes}).
   * Implementations backed by a persistence framework (e.g. based on Spring Data) override
   * it with the answer of the framework, which is the authority. Override it to return
   * <code>null</code> yourself if your persistence layer handles the serialized form
   * itself.
   *
   * @return The type of the ID property of the aggregate, or <code>null</code> if it cannot
   *         be determined
   */
  default Class<?> getAggregateIdType() {

    return AggregateIdTypes
        .determineIdType(getAggregateClass())
        .orElse(null);

  }

  /**
   * Whether this persistence layer notices that somebody else changed the aggregate in
   * between, instead of writing over it without a word.
   * <p>
   * VanillaBP asks while the application starts. It warns about an aggregate which a
   * handler may save and which nothing can protect. A second writer is not exotic. The
   * application writes from its own endpoint. A BPMN model with two tokens writes from two
   * branches. An extension writes while it reports. If the answer is <code>false</code>,
   * the later write wins, and nobody learns about the earlier one.
   * <p>
   * <b>This default does NOT throw</b> (unlike most of the others). It looks for the
   * attribute which a persistence layer increments on each write. It finds it by reflection,
   * and by the name every supported layer gives the annotation (see {@link VersionAttribute}).
   * A store which notices a second writer in another way overrides this and says so, for
   * example a ledger which appends, an event store, or a document store with its own
   * condition. Then there is no warning.
   *
   * @return Whether a concurrent change of this aggregate is noticed and not overwritten
   */
  default boolean detectsConcurrentModification() {

    return VersionAttribute.isDeclaredBy(getAggregateClass());

  }

  /**
   * Loads the aggregate by its ID. VanillaBP uses it e.g. when it processes BPMN tasks
   * (the aggregate is loaded, the business method is executed and the aggregate is saved
   * within one transaction).
   * <p>
   * The implementations of the platform (e.g. based on Spring Data) support this out of
   * the box. Custom implementations have to override this method.
   *
   * @param aggregateId The aggregate's ID (as returned by {@link #getAggregateId(Object)})
   * @return The aggregate, or <code>null</code> if there is none with the given ID
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
