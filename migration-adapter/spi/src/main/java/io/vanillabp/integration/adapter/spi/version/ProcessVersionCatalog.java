package io.vanillabp.integration.adapter.spi.version;

import java.util.List;

/**
 * What a BPMS knows about the deployed versions of a BPMN process - implemented by an
 * adapter whose BPMS can tell, handed to the core during <code>wireBpmn</code> using
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#registerProcessVersions}.
 * <p>
 * The core needs it only for version SPECIFICATIONS naming a version TAG
 * (<code>&#64;WorkflowTask(version = "release-2024")</code>, <code>version = "&gt;v1.4"</code>):
 * a specification consisting of numbers is compared to the version the BPMS reported
 * without asking anybody. That is why an adapter of a BPMS which cannot report tags
 * simply registers nothing - version specifications made of numbers keep working, and
 * a specification naming a tag matches nothing and is reported once with a guiding
 * message.
 * <p>
 * <b>Both directions are expected to be cheap:</b> {@link #resolveVersion} is called
 * while a task is dispatched, so an implementation caches what it learned and asks the
 * BPMS only for a version it has not seen yet. That case is real: during a rolling
 * deployment another cluster node may already have deployed a version this node does
 * not know, and the BPMS delivers a task of it before this node deploys. See
 * {@link CachingProcessVersionCatalog}, which implements exactly that and leaves the
 * BPMS query to the adapter.
 */
public interface ProcessVersionCatalog {

  /**
   * All versions of the given process the BPMS knows, ordered by deployment, oldest
   * first. Called once per process at startup (the deployment pipeline has finished
   * then, so the version deployed by this very boot is included) to resolve the
   * version tags the application's annotations name.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The deployed versions, oldest first; empty if the BPMS cannot tell
   */
  List<DeployedProcessVersion> deployedVersionsOf(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The version identified by a version identifier the BPMS reported, or the newest
   * version carrying the given version tag.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param versionOrVersionTag A version identifier or a version tag
   * @return The version or <code>null</code> if the BPMS does not know it
   */
  DeployedProcessVersion resolveVersion(
      String workflowModuleId,
      String bpmnProcessId,
      String versionOrVersionTag);

}
