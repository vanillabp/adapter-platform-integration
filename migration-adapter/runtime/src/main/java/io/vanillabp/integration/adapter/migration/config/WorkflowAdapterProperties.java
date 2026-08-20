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
   * Overrides <code>vanillabp.delivery</code> for this workflow. Only the settings which
   * belong to a single workflow are read here - the maximum age of an open task, since
   * one process may wait for a partner for weeks while every other one is done in
   * minutes.
   */
  private DeliveryProperties delivery;

}
