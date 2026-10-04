package io.vanillabp.integration.extension.spi.election;

/**
 * What VanillaBP wrote down when a workflow started: the adapter which started it, the BPMS' own
 * id of the workflow, and the version of the process definition it runs on.
 * <p>
 * All of them come from one row, so they are answered together. An extension which shows details
 * per process version needs the version as early as it needs the id, and a BPMS answering from a
 * read model has neither of them shortly after the start.
 * <p>
 * <strong>An empty version: "not yet" or "never"</strong>
 * <p>
 * A version can be missing for two reasons, and a reader has to tell them apart. Either the BPMS
 * of this workflow knows versions and nobody wrote this one down yet, or that BPMS has no versions
 * at all. {@link #versionsAreReported()} says which. Where it is <code>true</code>, an empty
 * version is "not yet": a later row of the same workflow may carry it. Where it is
 * <code>false</code>, an empty version stays empty, and waiting for it means waiting forever.
 *
 * @param adapterId The adapter which started the workflow. A workflow does not change its BPMS, so
 *          that adapter holds the workflow until its end. <code>null</code> where VanillaBP knows
 *          the id of the workflow from its election cache only, which names no adapter here
 * @param workflowId The BPMS' own id of the workflow, never <code>null</code>
 * @param processVersion The version of the process definition the workflow runs on, as the BPMS
 *          counts it (a number on Camunda 7 and Camunda 8, a version tag where the BPMS has only
 *          that). <code>null</code> where nothing VanillaBP holds names one
 * @param versionsAreReported Whether the adapter which started this workflow reports process
 *          versions for its BPMN process. <code>false</code> where it said it has none, and also
 *          where it said nothing about versions at all, because a reader who waits on an adapter
 *          which never answers waits forever
 */
public record WorkflowStart(
                            String adapterId,
                            String workflowId,
                            String processVersion,
                            boolean versionsAreReported) {

}
