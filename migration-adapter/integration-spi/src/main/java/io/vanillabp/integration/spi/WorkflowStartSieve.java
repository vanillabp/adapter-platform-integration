package io.vanillabp.integration.spi;

/**
 * The second sieve in front of deleting the row about the start of a workflow: whether the
 * workflow aggregate that row names still exists.
 *
 * <strong>Why a duration alone is a guess</strong>
 *
 * The row is read to answer "which workflow of the BPMS does this aggregate belong to", and
 * somebody may ask that for as long as the application keeps the aggregate. How long that is
 * belongs to the application and to nothing else, so
 * <code>vanillabp.delivery.workflow-start-retention</code> is a number somebody picked. This
 * asks instead: the aggregate is loaded by its id, and a persistence which answers
 * <code>null</code> says the aggregate is gone and nobody will ask about it again.
 *
 * <strong>What the answers mean</strong>
 *
 * <code>true</code> deletes the row, <code>false</code> keeps it, and <code>null</code> means
 * the sieve cannot say. A row the sieve cannot judge is KEPT: a custom
 * {@link AggregatePersistenceAware} which does not implement
 * {@link AggregatePersistenceAware#loadById} answers with an exception, and reading that as
 * "gone" would delete exactly the rows nobody can replace.
 * <p>
 * Costs one read per expired row, which is why an application switches it on
 * (<code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>) rather than
 * finding it switched on. Without it the retention decides alone, which is the rule an
 * installation can rely on either way.
 */
public interface WorkflowStartSieve {

  /**
   * Whether the row about this workflow start may be deleted now that its retention passed.
   *
   * @param workflowStart The row, which names the workflow module, the BPMN process and the
   *          workflow aggregate
   * @return <code>true</code> to delete it, <code>false</code> to keep it, <code>null</code>
   *         where this sieve cannot say - which keeps it as well
   */
  Boolean mayBeDeleted(
      TaskDelivery workflowStart);

}
