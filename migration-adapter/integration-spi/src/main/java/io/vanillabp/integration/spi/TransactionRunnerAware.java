package io.vanillabp.integration.spi;

/**
 * Assigns a {@link TransactionRunner} to the workflow aggregates it serves. This is the
 * hook for an application whose persistence is not covered by the transaction manager of
 * the platform (an event store, a ledger, a message producer, a service behind an API).
 * <p>
 * VanillaBP wraps everything it does around a workflow aggregate in one transaction.
 * Loading the aggregate, invoking the <code>&#64;WorkflowTask</code> handler, saving
 * the aggregate, writing the delivery record and scheduling a phase-two outbox entry
 * either all commit, or none of them does. This interface says which transaction that is.
 * <p>
 * <b>Resolution</b> works like for {@link AggregatePersistenceAware},
 * {@link PhaseTwoOutboxAware} and {@link TaskDeliveryLogAware}. Among the beans of this
 * type whose {@link #getAggregateClass()} the aggregate is assignable to, the one with
 * the smallest inheritance distance serves it. Interfaces count. So one bean can serve
 * every aggregate of a workflow module through an interface they share, and a bean which
 * names a single aggregate class still wins over it. If two beans have the same
 * distance, the boot ends and names both of them. A transaction boundary must not depend
 * on the order in which beans are found.
 * <p>
 * If no bean of this type covers an aggregate, VanillaBP uses a plain
 * {@link TransactionRunner} bean of the application if there is one. Otherwise it uses
 * the own runner of the platform.
 * <p>
 * <b>Embedded BPMS:</b> an embedded engine (Camunda 7) invokes handlers inside the own
 * transaction of the engine, and VanillaBP then calls
 * {@link TransactionRunner#inCurrent(java.util.function.Supplier)}. An implementation
 * has to JOIN in that case, and must not open something of its own. Otherwise the
 * aggregate is written outside the transaction the engine commits. Note that an
 * embedded engine needs a relational database, so its transaction can never cover a
 * storage of another technology anyway.
 *
 * <p>
 * Why an application may own the transaction VanillaBP runs its work in is decision 11 in the
 * repository's DECISIONS.md.
 *
 * @param <A> The workflow-aggregate class served, or a superclass or interface of
 *          several of them
 */
public interface TransactionRunnerAware<A> {

  /**
   * The workflow-aggregate class this runner serves. It may be a superclass or an
   * interface shared by several aggregates. The most specific bean wins.
   *
   * @return The aggregate class, superclass or interface served
   */
  Class<A> getAggregateClass();

  /**
   * The runner which provides the transaction for the aggregates served.
   *
   * @return The transaction runner, never <code>null</code>
   */
  TransactionRunner getTransactionRunner();

}
