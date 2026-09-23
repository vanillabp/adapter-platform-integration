package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * What an application says about ONE workflow, which is one BPMN process of one workflow
 * module (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.*</code>, the
 * key being the plain BPMN process id).
 * <p>
 * This is the third of the four levels an adapter setting may be written at, between the
 * workflow module and the single task (decision 7 in the repository's DECISIONS.md), and it
 * is the only level at which a workflow may be allowed to hand its whole aggregate to the
 * BPMS.
 */
@Getter
@Setter
@SuperBuilder
public class WorkflowAdapterProperties extends AdaptersConfigurationProperties {

  /**
   * The BPMN process id this section is about, taken from the key the application wrote it
   * under. Written by {@link MigrationAdapterProperties#validateAndLink()} rather than by
   * the binder, so a section which was built by hand carries it only after that call.
   */
  String bpmnProcessId;

  /**
   * The workflow module this workflow belongs to - the way back up, which is what lets a
   * message about this workflow name its module. Written by
   * {@link MigrationAdapterProperties#validateAndLink()} like the id above.
   */
  WorkflowModuleAdapterProperties workflowModule;

  /**
   * The empty section a configuration binder starts from, one per configured workflow:
   * both platforms create the object and then write the keys the application configured
   * into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would leave the maps below <code>null</code> instead of empty.
   */
  public WorkflowAdapterProperties() {

    this(builder());

  }

  /**
   * The properties of adapters specific to this workflow. Keys are the adapter IDs.
   */
  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The properties of the workflow's BPMN tasks. Keys are the task IDs (task
   * definitions), and what may stand below one of them is {@link TaskAdapterProperties} -
   * the most specific level of the four.
   */
  @Builder.Default
  private Map<String, TaskAdapterProperties> tasks = Map.of();

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow -
   * what an extension is told about ONE BPMN process, which is the level below the
   * workflow module. Keys are the extension ids, and a key the workflow says nothing
   * about keeps what the workflow module or the global section says (see
   * {@link MigrationAdapterProperties#resolveForExtension}).
   */
  @Builder.Default
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
   * place this permission may be written, see decision 66 in the repository's
   * DECISIONS.md. An aggregate which keeps nothing back ends the startup until this
   * stands at the workflow it is about.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow. Only the settings which
   * belong to a single workflow are read here - the maximum age of an open task, since
   * one process may wait for a partner for weeks while every other one is done in
   * minutes.
   */
  private DeliveryProperties delivery;

}
