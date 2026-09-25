package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * What an application says about ONE workflow module (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.*</code>, the key being the module id
 * from its <code>META-INF/workflow-module</code> descriptor).
 * <p>
 * It is the second of the four levels an adapter setting may be written at, below the
 * adapter's own section and above the single workflow (decision 7 in the repository's
 * DECISIONS.md). Next to those adapter keys it may say for its own workflows what the
 * global sections say for the whole application: which adapters serve, how transactions,
 * the election and the delivery records are treated, and what an extension is told.
 * <p>
 * A module which configures nothing still gets a section: the classpath facts derive one
 * per module found there (decision 8 in the repository's DECISIONS.md).
 */
@Getter
@Setter
@SuperBuilder
public class WorkflowModuleAdapterProperties extends AdaptersConfigurationProperties {

  /**
   * The workflow module this section is about, taken from the key the application wrote it
   * under. Written by {@link MigrationAdapterProperties#validateAndLink()} rather than by
   * the binder, so a section which was built by hand carries it only after that call.
   */
  String workflowModuleId;

  /**
   * What the adapters are told for this workflow module. Keys are the adapter ids, and
   * what may stand below one of them is {@link AdapterProperties} - the same keys as at
   * the three other levels.
   */
  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The empty section a configuration binder starts from, one per configured workflow
   * module: both platforms create the object and then write the keys the application
   * configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would leave the maps of this section <code>null</code> instead of
   * empty.
   */
  public WorkflowModuleAdapterProperties() {

    this(builder());

  }

  /**
   * The workflows of the workflow module. The key is the BPMN process ID.
   * <p>
   * <i>Hint:</i> Back-references (BPMN process ID, workflow module) are linked by
   * {@link MigrationAdapterProperties#validateAndLink()}.
   */
  @Builder.Default
  private Map<String, WorkflowAdapterProperties> workflows = Map.of();

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md). It is
   * bound at this level so that a line written here is answered with a message saying
   * where it belongs, instead of being ignored.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
   * developer declared (<code>declared-task-params</code>). The second least specific of
   * the four levels; a task, and then a workflow, outranks it.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Overrides <code>vanillabp.transactions</code> for this workflow module. A setting
   * left undefined here means the global one applies, so a single module can accept
   * unguarded writes while every other one keeps failing the startup check.
   */
  private TransactionsProperties transactions;

  /**
   * Overrides <code>vanillabp.election</code> for this workflow module. A setting
   * left out here means "whatever is configured globally".
   */
  private ElectionProperties election;

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow module. A setting left
   * undefined here means the global one applies, so one module can release the records of
   * its ended workflows while another keeps them for support.
   */
  private DeliveryProperties delivery;

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
   * module - the place an extension configured once for the whole application says
   * something different about one of its modules (the Business Cockpit's URI of a module,
   * say). Keys are the extension ids.
   */
  @Builder.Default
  private Map<String, Map<String, String>> extensions = Map.of();

}
