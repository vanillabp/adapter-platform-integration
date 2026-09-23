package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
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
 * It is read where a single task is the reason for a setting: whether the deliveries of
 * that task are deduplicated (<code>deduplicate-deliveries</code> of
 * {@link AdapterProperties}) and how long it may stay open (<code>max-task-age</code> of
 * {@link DeliveryProperties}). An adapter setting of its own, a per-task job timeout say,
 * needs nothing here beyond its key.
 * <p>
 * Why an adapter setting can be written at four levels, and which of them wins, is decision 7 in
 * the repository's DECISIONS.md.
 */
@Getter
@Setter
@SuperBuilder
public class TaskAdapterProperties {

  /**
   * The empty section a configuration binder starts from, one per task an application
   * writes something about: both platforms create the object and then write the keys into
   * it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would leave the maps below <code>null</code> instead of empty.
   */
  public TaskAdapterProperties() {

    this(builder());

  }

  /**
   * The properties of adapters specific to this task. Keys are the adapter IDs.
   */
  @Builder.Default
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task - the
   * most specific level an extension setting may be written at. Keys are the extension
   * ids, and a key the task says nothing about keeps what the three less specific levels
   * say (see {@link MigrationAdapterProperties#resolveForExtension}).
   */
  @Builder.Default
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
   * the maximum age of an open task may be set at, which is where it belongs: the one
   * task waiting for a signature is the reason the whole application does not get a
   * longer age.
   */
  private DeliveryProperties delivery;

}
