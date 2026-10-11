package io.vanillabp.integration.spi;

/**
 * The second filter before the row about the start of a workflow is deleted: does the
 * workflow aggregate which that row names still exist?
 *
 * <strong>Why a duration alone is a guess</strong>
 *
 * The row is read to answer "which workflow of the BPMS does this aggregate belong to".
 * Somebody may ask that as long as the application keeps the aggregate. Only the
 * application knows how long that is. So
 * <code>vanillabp.delivery.workflow-start-retention</code> is a number somebody picked. This
 * filter asks instead. It loads the aggregate by its id. If the persistence answers
 * <code>null</code>, the aggregate is gone, and nobody will ask about it again.
 *
 * <strong>What the answers mean</strong>
 *
 * <code>true</code> deletes the row, <code>false</code> keeps it, and <code>null</code> means
 * that the filter cannot say. A row the filter cannot judge is KEPT. A custom
 * {@link AggregatePersistenceAware} which does not implement
 * {@link AggregatePersistenceAware#loadById} answers with an exception. Reading that as
 * "gone" would delete exactly the rows nobody can replace.
 * <p>
 * It costs one read per expired row. That is why an application has to switch it on
 * (<code>vanillabp.delivery.keep-workflow-start-while-aggregate-exists</code>), and does
 * not find it switched on. Without it, the retention decides alone. An installation can
 * rely on that rule either way.
 */
public interface WorkflowStartSieve {

  /**
   * Whether the row about this workflow start may be deleted, now that its retention has passed.
   *
   * @param workflowStart The row, which names the workflow module, the BPMN process and the
   *          workflow aggregate
   * @return <code>true</code> to delete it, <code>false</code> to keep it, <code>null</code>
   *         if this filter cannot say, which keeps it as well
   */
  Boolean mayBeDeleted(
      TaskDelivery workflowStart);

}
