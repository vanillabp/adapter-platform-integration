package io.vanillabp.integration.spi;

/**
 * Attributes a {@link TransactionRunner} to the workflow aggregates it serves - the
 * hook for an application whose persistence is not covered by the platform's
 * transaction manager (an event store, a ledger, a message producer, a service behind
 * an API).
 * <p>
 * VanillaBP wraps everything it does around a workflow aggregate in one transaction:
 * loading the aggregate, invoking the <code>&#64;WorkflowTask</code> handler, saving
 * the aggregate, writing the delivery record and scheduling a phase-two outbox entry
 * either all commit or none of them do. Which transaction that is, is answered here.
 * <p>
 * <b>Resolution</b> mirrors {@link AggregatePersistenceAware},
 * {@link PhaseTwoOutboxAware} and {@link TaskDeliveryLogAware}: among the beans of
 * this type whose {@link #getAggregateClass()} the aggregate is assignable to, the one
 * with the smallest inheritance distance serves it. Interfaces count, so one bean can
 * serve every aggregate of a workflow module through an interface they share, and a
 * bean naming a single aggregate class still wins over it. Two beans at the same
 * distance end the boot naming both of them - a transaction boundary must not depend
 * on the order beans are found in.
 * <p>
 * Where no bean of this type covers an aggregate, VanillaBP uses a plain
 * {@link TransactionRunner} bean of the application if there is one, and the
 * platform's own runner otherwise.
 * <p>
 * <b>Embedded BPMS:</b> an embedded engine (Camunda 7) invokes handlers inside the
 * engine's own transaction, and VanillaBP then calls
 * {@link TransactionRunner#inCurrent(java.util.function.Supplier)}. An implementation
 * has to JOIN in that case rather than open something of its own, otherwise the
 * aggregate is written outside the transaction the engine commits. Note that an
 * embedded engine needs a relational database, so its transaction can never cover a
 * storage of a different technology anyway.
 *
 * @param <A> The workflow-aggregate class served, or a superclass respectively
 *          interface of several of them
 */
public interface TransactionRunnerAware<A> {

  /**
   * The workflow-aggregate class this runner serves. May be a superclass or an
   * interface shared by several aggregates - the most specific bean wins.
   *
   * @return The aggregate class, superclass or interface served
   */
  Class<A> getAggregateClass();

  /**
   * The runner providing the transaction for the aggregates served.
   *
   * @return The transaction runner, never <code>null</code>
   */
  TransactionRunner getTransactionRunner();

}
