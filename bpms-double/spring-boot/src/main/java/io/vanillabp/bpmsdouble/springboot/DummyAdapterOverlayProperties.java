package io.vanillabp.bpmsdouble.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree - the reference implementation of the pattern every VanillaBP adapter uses on
 * Spring Boot to contribute its own keys (e.g. connection settings) to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>: a second
 * {@code @ConfigurationProperties("vanillabp")} class coexists with the platform's
 * binding of the core model (same-prefix classes bind side by side; keys unknown to
 * either view are ignored by the JavaBean binding).
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from
 * the platform's core properties ({@code MigrationAdapterProperties.adapterTypes()});
 * the overlay is a per-known-id lookup only (environment-variable overrides can
 * materialize phantom map entries in the overlay).
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the dummy
   * adapter's own keys are modeled here.
   */
  private Map<String, DummyAdapterConfig> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module ID -
   * the overlay mirrors the levels of the most-specific-wins resolution of
   * adapter-scoped properties (task &gt; workflow &gt; workflow-module &gt;
   * adapter), so scope-specific adapter keys (like a per-task job timeout of a real
   * BPMS) resolve from real application configuration.
   */
  private Map<String, ModuleOverlay> workflowModules = Map.of();

  /**
   * Spring's configuration binder creates the empty overlay and fills the two maps
   * above through their setters. A test never builds one: it writes the keys into the
   * application configuration and asks the container for this bean.
   */
  public DummyAdapterOverlayProperties() {
  }

  /**
   * Resolves the dummy adapter's <code>test</code> key with most-specific-wins
   * semantics across the four levels - the reference implementation of how a real
   * adapter resolves its scope-specific keys from its overlay (the core's
   * <code>resolveForAdapter</code> covers the core-owned keys; overlay keys are
   * walked by the adapter itself, same order).
   *
   * @param workflowModuleId The workflow module ID or <code>null</code>
   * @param bpmnProcessId The BPMN process ID or <code>null</code>
   * @param taskId The task ID (task definition) or <code>null</code>
   * @param adapterId The adapter ID
   * @return The most specific configured value or <code>null</code>
   */
  public Integer testFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId,
      final String adapterId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module.getWorkflows().get(bpmnProcessId)
        : null;
    final var task = (workflow != null) && (taskId != null)
        ? workflow.getTasks().get(taskId)
        : null;

    final var levelsMostSpecificFirst = new java.util.LinkedList<Map<String, DummyAdapterConfig>>();
    if (task != null) {
      levelsMostSpecificFirst.add(task.getAdapters());
    }
    if (workflow != null) {
      levelsMostSpecificFirst.add(workflow.getAdapters());
    }
    if (module != null) {
      levelsMostSpecificFirst.add(module.getAdapters());
    }
    levelsMostSpecificFirst.add(adapters);
    return levelsMostSpecificFirst
        .stream()
        .map(level -> level.get(adapterId))
        .filter(java.util.Objects::nonNull)
        .map(DummyAdapterConfig::getTest)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter id
   *
   * @return The dummy adapter's keys per adapter id
   */
  public Map<String, DummyAdapterConfig> getAdapters() {

    return adapters;

  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id
   *
   * @return The overlay per workflow module
   */
  public Map<String, ModuleOverlay> getWorkflowModules() {

    return workflowModules;

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter id
   *
   * @param adapters The dummy adapter's keys per adapter id
   */
  public void setAdapters(
      final Map<String, DummyAdapterConfig> adapters) {

    this.adapters = adapters;

  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id
   *
   * @param workflowModules The overlay per workflow module
   */
  public void setWorkflowModules(
      final Map<String, ModuleOverlay> workflowModules) {

    this.workflowModules = workflowModules;

  }

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  public static class DummyAdapterConfig {

    /**
     * A test value used by the platform integration's tests to prove that
     * adapter-specific keys inside the shared tree are tolerated and reach the
     * adapter's overlay typed.
     */
    private Integer test;

    /**
     * The binder creates the empty section and sets <code>test</code> from the
     * configuration of that level.
     */
    public DummyAdapterConfig() {
    }

    /**
     * A test value which proves that an adapter-specific key inside the shared tree
     * reaches the adapter's overlay typed
     *
     * @return The value written at this level, or <code>null</code> where this level writes none
     */
    public Integer getTest() {

      return test;

    }

    /**
     * A test value which proves that an adapter-specific key inside the shared tree
     * reaches the adapter's overlay typed
     *
     * @param test The value of this level, or <code>null</code>
     */
    public void setTest(
        final Integer test) {

      this.test = test;

    }

  }

  /**
   * The dummy adapter's view of one workflow-module section.
   */
  public static class ModuleOverlay {

    /**
     * The dummy adapter's keys written at this workflow module, keyed by adapter id.
     */
    private Map<String, DummyAdapterConfig> adapters = Map.of();

    /**
     * The workflow sections below this workflow module, keyed by BPMN process id.
     */
    private Map<String, WorkflowOverlay> workflows = Map.of();

    /**
     * The binder creates the empty section and fills its two maps while it walks down
     * the configuration tree.
     */
    public ModuleOverlay() {
    }

    /**
     * The dummy adapter's keys written at this workflow module, keyed by adapter id
     *
     * @return The dummy adapter's keys per adapter id
     */
    public Map<String, DummyAdapterConfig> getAdapters() {

      return adapters;

    }

    /**
     * The workflow sections below this workflow module, keyed by BPMN process id
     *
     * @return The overlay per workflow
     */
    public Map<String, WorkflowOverlay> getWorkflows() {

      return workflows;

    }

    /**
     * The dummy adapter's keys written at this workflow module, keyed by adapter id
     *
     * @param adapters The dummy adapter's keys per adapter id
     */
    public void setAdapters(
        final Map<String, DummyAdapterConfig> adapters) {

      this.adapters = adapters;

    }

    /**
     * The workflow sections below this workflow module, keyed by BPMN process id
     *
     * @param workflows The overlay per workflow
     */
    public void setWorkflows(
        final Map<String, WorkflowOverlay> workflows) {

      this.workflows = workflows;

    }

  }

  /**
   * The dummy adapter's view of one workflow section.
   */
  public static class WorkflowOverlay {

    /**
     * The dummy adapter's keys written at this workflow, keyed by adapter id.
     */
    private Map<String, DummyAdapterConfig> adapters = Map.of();

    /**
     * The task sections below this workflow, keyed by task definition.
     */
    private Map<String, TaskOverlay> tasks = Map.of();

    /**
     * The binder creates the empty section and fills its two maps while it walks down
     * the configuration tree.
     */
    public WorkflowOverlay() {
    }

    /**
     * The dummy adapter's keys written at this workflow, keyed by adapter id
     *
     * @return The dummy adapter's keys per adapter id
     */
    public Map<String, DummyAdapterConfig> getAdapters() {

      return adapters;

    }

    /**
     * The task sections below this workflow, keyed by task definition
     *
     * @return The overlay per task
     */
    public Map<String, TaskOverlay> getTasks() {

      return tasks;

    }

    /**
     * The dummy adapter's keys written at this workflow, keyed by adapter id
     *
     * @param adapters The dummy adapter's keys per adapter id
     */
    public void setAdapters(
        final Map<String, DummyAdapterConfig> adapters) {

      this.adapters = adapters;

    }

    /**
     * The task sections below this workflow, keyed by task definition
     *
     * @param tasks The overlay per task
     */
    public void setTasks(
        final Map<String, TaskOverlay> tasks) {

      this.tasks = tasks;

    }

  }

  /**
   * The dummy adapter's view of one task section - the MOST specific level.
   */
  public static class TaskOverlay {

    /**
     * The dummy adapter's keys written at this task, keyed by adapter id.
     */
    private Map<String, DummyAdapterConfig> adapters = Map.of();

    /**
     * The binder creates the empty section and fills its adapter map. This is the most
     * specific of the four levels, so a value set here wins.
     */
    public TaskOverlay() {
    }

    /**
     * The dummy adapter's keys written at this task, keyed by adapter id
     *
     * @return The dummy adapter's keys per adapter id
     */
    public Map<String, DummyAdapterConfig> getAdapters() {

      return adapters;

    }

    /**
     * The dummy adapter's keys written at this task, keyed by adapter id
     *
     * @param adapters The dummy adapter's keys per adapter id
     */
    public void setAdapters(
        final Map<String, DummyAdapterConfig> adapters) {

      this.adapters = adapters;

    }

  }

}
