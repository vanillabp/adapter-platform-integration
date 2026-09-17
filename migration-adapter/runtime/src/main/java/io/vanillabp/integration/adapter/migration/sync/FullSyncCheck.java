package io.vanillabp.integration.adapter.migration.sync;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Refuses to start an application whose workflow aggregate shares EVERYTHING with the
 * BPMS, as long as the workflow did not allow exactly that.
 * <p>
 * An aggregate carrying no {@code @NoSyncWithBPMS} anywhere hands every attribute it
 * reaches to the BPMS, at every sync point, and on a remote BPMS that means the values
 * leave the application. It is not a decision anybody takes, it is where an application
 * lands by doing nothing, which is why it is said out loud once instead of being written
 * into a log nobody reads.
 * <p>
 * The way out an application usually wants is to say what its models really need. The
 * other one is the permission, and it belongs to the single workflow: see decision 66 in
 * the repository's DECISIONS.md for why it is not inherited from anywhere.
 */
public class FullSyncCheck {

  private final WorkflowAggregateSync aggregateSync;

  private final MigrationAdapterProperties properties;

  /**
   * @param aggregateSync The core's sync model, which answers what an aggregate shares
   * @param properties The VanillaBP configuration, which holds the permission
   */
  public FullSyncCheck(
      final WorkflowAggregateSync aggregateSync,
      final MigrationAdapterProperties properties) {

    this.aggregateSync = aggregateSync;
    this.properties = properties;

  }

  /**
   * Ends the startup where the workflow shares its entire aggregate without saying so.
   * Called once per registered workflow, which is as early as the permission can be
   * read: the sync model comes from the classes and the permission from the
   * configuration, and both are there when the workflow services are registered.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID being registered
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class declaring it
   * @param workflowAggregateClass The workflow aggregate of that workflow
   * @param aggregateIdAttribute The name of the aggregate's ID attribute, or
   *          <code>null</code> where the persistence does not name one
   * @throws IllegalStateException Naming the workflow, the aggregate, what it shares and
   *           the two ways out
   */
  public void refuseSharingEverythingUnlessAllowed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final String aggregateIdAttribute) {

    if ((aggregateSync == null) || (properties == null)) {
      return;
    }
    if (isSecondaryProcessOf(workflowServiceClass, bpmnProcessId)) {
      // a secondary process runs on the workflow of the primary one, so the permission
      // of the primary process covers it - it is the id the configuration knows
      return;
    }
    if (properties.allowsFullSyncWithBpms(workflowModuleId, bpmnProcessId)) {
      return;
    }
    // a persistence which does not name the ID attribute leaves the conventional name,
    // which is the one every persistence VanillaBP ships derives anyway: an aggregate made
    // of nothing but its ID must not be refused because nobody could name that attribute
    final var idAttribute = aggregateIdAttribute != null
        ? aggregateIdAttribute
        : "id";
    final var shared = aggregateSync.everythingSharedWithBpms(workflowAggregateClass, idAttribute);
    if (shared.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            The workflow aggregate '%s' of BPMN process '%s' of workflow module '%s' shares \
            EVERY attribute with the BPMS: %s. Nothing is annotated @NoSyncWithBPMS, so each of \
            them becomes a process variable at every sync point, together with everything \
            hanging on them - and a BPMS running outside this application is a place they \
            leave it for.

            There are two ways on. Tell the aggregate what the model really needs: annotate the \
            class @NoSyncWithBPMS and each attribute a BPMN expression reads @SyncWithBPMS. Or \
            allow the full sync for THIS workflow:

              %s: true

            The permission belongs to the workflow. It is not inherited, so the same line at the \
            workflow module, at the application or at an adapter does nothing: it would allow \
            the next workflow somebody adds as well, and nobody looked at that one."""
            .formatted(
                workflowAggregateClass.getName(),
                bpmnProcessId,
                workflowModuleId,
                String.join(", ", shared),
                MigrationAdapterProperties.allowFullSyncWithBpmsProperty(workflowModuleId, bpmnProcessId)));

  }

  /**
   * Whether that BPMN process is one of the <code>secondaryBpmnProcesses</code> of the
   * declaring class rather than the process it primarily serves.
   *
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param bpmnProcessId The BPMN process ID being registered
   * @return Whether the class names it as a secondary process
   */
  private static boolean isSecondaryProcessOf(
      final Class<?> workflowServiceClass,
      final String bpmnProcessId) {

    if (workflowServiceClass == null) {
      return false;
    }
    final var annotation = workflowServiceClass.getAnnotation(WorkflowService.class);
    if (annotation == null) {
      return false;
    }
    for (final var secondary : annotation.secondaryBpmnProcesses()) {
      if (secondary.bpmnProcessId().equals(bpmnProcessId)) {
        return true;
      }
    }
    return false;

  }

}
