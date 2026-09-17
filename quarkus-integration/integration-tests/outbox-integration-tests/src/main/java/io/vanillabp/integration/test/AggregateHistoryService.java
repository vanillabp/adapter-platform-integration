package io.vanillabp.integration.test;

/**
 * The per-aggregate service of the sample extension of this scenario, offering the two
 * things an extension needs to report the state of an event: the name of the state it
 * sees while it plans its outbox entry, and that state again when the entry is
 * dispatched.
 * <p>
 * VanillaBP builds one of these per workflow aggregate, out of
 * {@link AggregateHistoryServiceFactory}, which is how an extension reaches the
 * persistence of an aggregate without knowing anything about it.
 *
 * @param <A> The workflow-aggregate type
 */
public interface AggregateHistoryService<A> {

  /**
   * @param workflowAggregate The aggregate an entry is about to be planned for
   * @return The auditing id of its current state, or <code>null</code> where the
   *         application keeps no history
   */
  String auditingIdOf(
      A workflowAggregate);

  /**
   * @param workflowAggregateId The aggregate's ID
   * @param auditingId The state wanted, or <code>null</code> for the state of now
   * @return The aggregate as it was at that state
   */
  A asItWasAt(
      Object workflowAggregateId,
      String auditingId);

}
