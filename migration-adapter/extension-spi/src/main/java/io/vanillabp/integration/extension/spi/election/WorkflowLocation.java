package io.vanillabp.integration.extension.spi.election;

/**
 * Where a workflow is: which BPMS holds it, and what that BPMS calls it.
 * <p>
 * Both values stand in the same place. The record VanillaBP writes for every task delivery
 * keeps the workflow id next to the adapter id, so an extension which was handed only the
 * adapter id was handed half of one row and had to ask again for the rest.
 *
 * @param adapterId The id of the adapter holding the workflow - never <code>null</code>,
 *          because the election either answers or fails
 * @param workflowId The BPMS' own id of the workflow: the process instance key of
 *          Camunda 8, the process instance id of an embedded engine, whatever that BPMS
 *          talks about a running instance in. <code>null</code> where nothing VanillaBP
 *          holds knew one, which is a regular answer and not an error
 */
public record WorkflowLocation(
                               String adapterId,
                               String workflowId) {

}
