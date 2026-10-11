package io.vanillabp.integration.extension.spi.election;

/**
 * Where a workflow is: which BPMS holds it, and what that BPMS calls it.
 * <p>
 * Both values are stored in the same place. The record VanillaBP writes for every task
 * delivery keeps the workflow id next to the adapter id. An extension which got only the
 * adapter id got half of one row, and had to ask again for the rest.
 *
 * @param adapterId The id of the adapter which holds the workflow. Never <code>null</code>,
 *          because the election either answers or fails
 * @param workflowId The BPMS' own id of the workflow: the process instance key of
 *          Camunda 8, the process instance id of an embedded engine, or whatever that BPMS
 *          calls a running instance. <code>null</code> if nothing VanillaBP holds knows
 *          one. That is a regular answer, not an error
 */
public record WorkflowLocation(
                               String adapterId,
                               String workflowId) {

}
