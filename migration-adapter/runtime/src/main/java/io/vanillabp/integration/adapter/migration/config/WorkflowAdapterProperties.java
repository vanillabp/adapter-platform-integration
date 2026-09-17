package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Properties passed by platform integration implementations.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class WorkflowAdapterProperties extends AdaptersConfigurationProperties {

  String bpmnProcessId;

  WorkflowModuleAdapterProperties workflowModule;

  /**
   * The properties of adapters specific to this workflow. Keys are the adapter IDs.
   */
  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The properties of the workflow's BPMN tasks. Keys are the task IDs (task
   * definitions). Structural preparation for task-scoped adapter configuration -
   * no consumer yet.
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
