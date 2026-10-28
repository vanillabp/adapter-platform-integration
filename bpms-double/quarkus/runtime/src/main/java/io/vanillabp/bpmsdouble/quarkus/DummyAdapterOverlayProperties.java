package io.vanillabp.bpmsdouble.quarkus;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. It is the reference for the pattern every VanillaBP adapter extension uses to
 * add its own keys (for example connection settings) at the usual place of each
 * adapter, <code>vanillabp.adapters.&lt;id&gt;.*</code>:
 * <ul>
 *   <li>A second RUN_TIME {@code @ConfigMapping} over the same prefix lives next to the
 *       platform's mapping. Keys found in both are served to both.</li>
 *   <li>SmallRye's check for unknown keys passes if ANY registered mapping knows a key.
 *       So platform keys ({@code type}, {@code deployment-failure}) and adapter keys
 *       ({@code test}) make each other's sections valid. No general
 *       {@code withMappingIgnore} is needed, so a typo under {@code vanillabp.*} fails
 *       the startup again.</li>
 *   <li>The set of adapter ids NEVER comes from this overlay map. It always comes from
 *       the platform's core properties ({@code MigrationAdapterProperties.adapterTypes()}).
 *       The overlay is only looked up by ids already known.</li>
 * </ul>
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID. Only the dummy
   * adapter's own keys are modeled here.
   *
   * @return The sections keyed by adapter ID, empty where an application configures
   *         none of them - a lookup map, never the source of the ids
   */
  @ConfigDocMapKey("adapter-id")
  Map<String, DummyAdapterConfig> adapters();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module ID. The
   * overlay has the same levels as the core, where the most specific value of an adapter
   * property wins (task &gt; workflow &gt; workflow-module &gt; adapter). So adapter keys
   * set for one level, like the job timeout of one task in a real BPMS, are read from
   * real application configuration.
   *
   * @return The sections keyed by workflow module ID, empty where no module sets a key
   *         of this adapter
   */
  @ConfigDocMapKey("workflow-module-id")
  Map<String, ModuleOverlay> workflowModules();

  /**
   * Resolves the dummy adapter's <code>test</code> key over the four levels. The most
   * specific value wins. This is the reference for how a real adapter resolves its own
   * keys per level from its overlay. The core's <code>resolveForAdapter</code> covers the
   * keys the core owns. The adapter walks its overlay keys itself, in the same order.
   *
   * @param workflowModuleId The workflow module ID or <code>null</code>
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskId The task ID (task definition) or <code>null</code>
   * @param adapterId The adapter ID
   * @return The most specific configured value or <code>null</code>
   */
  default Integer testFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.workflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.tasks().get(taskId)
        : null;

    final var levelsMostSpecificFirst = new java.util.LinkedList<Map<String, DummyAdapterConfig>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.adapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.adapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.adapters());
    }
    levelsMostSpecificFirst.add(adapters());
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(java.util.Objects::nonNull)
        .map(DummyAdapterConfig::test)
        .flatMap(Optional::stream)
        .findFirst()
        .orElse(null);

  }

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  interface DummyAdapterConfig {

    /**
     * A test value. The platform integration's tests use it to prove two things: keys of
     * an adapter inside the shared tree are accepted, and they reach the adapter's
     * overlay with their type.
     *
     * @return The value set on the level of this section, or empty where that level sets
     *         nothing. {@link DummyAdapterOverlayProperties#testFor} walks the levels and
     *         takes the first one which sets it
     */
    Optional<Integer> test();

  }

  /**
   * The dummy adapter's view of one workflow-module section.
   */
  interface ModuleOverlay {

    /**
     * The module-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, DummyAdapterConfig> adapters();

    /**
     * The workflow sections of the module, keyed by BPMN process ID.
     *
     * @return The workflow sections
     */
    @ConfigDocMapKey("bpmn-process-id")
    Map<String, WorkflowOverlay> workflows();

  }

  /**
   * The dummy adapter's view of one workflow section.
   */
  interface WorkflowOverlay {

    /**
     * The workflow-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, DummyAdapterConfig> adapters();

    /**
     * The task sections of the workflow, keyed by task ID (task definition).
     *
     * @return The task sections
     */
    @ConfigDocMapKey("task-definition")
    Map<String, TaskOverlay> tasks();

  }

  /**
   * The dummy adapter's view of one task section. This is the MOST specific level.
   */
  interface TaskOverlay {

    /**
     * The task-level adapter sections, keyed by adapter ID.
     *
     * @return The adapter sections
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, DummyAdapterConfig> adapters();

  }

}
