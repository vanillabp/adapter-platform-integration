package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Properties of a single BPMN task of a workflow
 * (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.*</code>).
 * The task level is the MOST specific level of the most-specific-wins resolution of
 * adapter-scoped properties (see
 * {@link MigrationAdapterProperties#resolveForAdapter}).
 * <p>
 * <b>Attention:</b> This level is structural preparation for task-scoped adapter
 * configuration (e.g. a per-task job timeout) - there is no consumer yet, and
 * workflow-level configuration (including tasks) is still rejected at startup.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class TaskAdapterProperties {

  /**
   * The properties of adapters specific to this task. Keys are the adapter IDs.
   */
  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

}
