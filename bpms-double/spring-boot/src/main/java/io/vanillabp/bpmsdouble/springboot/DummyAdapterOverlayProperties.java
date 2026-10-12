package io.vanillabp.bpmsdouble.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. It is the reference for the pattern every VanillaBP adapter uses on Spring Boot
 * to add its own keys (for example connection settings) at the usual place of each
 * adapter, <code>vanillabp.adapters.&lt;id&gt;.*</code>. A second
 * {@code @ConfigurationProperties("vanillabp")} class lives next to the platform's
 * binding of the core model. Classes with the same prefix bind side by side. The
 * JavaBean binding ignores keys which one of the two views does not know.
 * <p>
 * The set of adapter ids NEVER comes from this overlay map. It always comes from the
 * platform's core properties ({@code MigrationAdapterProperties.adapterTypes()}). The
 * overlay is only looked up by ids already known. The reason: environment variables can
 * create map entries in the overlay which belong to no adapter.
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
public class DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID. Only the dummy
   * adapter's own keys are modeled here.
   */
  private Map<String, DummyAdapterConfig> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module ID. The
   * overlay has the same levels as the core, where the most specific value of an adapter
   * property wins (task &gt; workflow &gt; workflow-module &gt; adapter). So adapter keys
   * set for one level, like the job timeout of one task in a real BPMS, are read from
   * real application configuration.
   */
  private Map<String, ModuleOverlay> workflowModules = Map.of();

  /**
   * Spring's configuration binder creates the empty overlay and fills the two maps above
   * through their setters. A test never builds one. It writes the keys into the
   * application configuration and asks the container for this bean.
   */
  public DummyAdapterOverlayProperties() {
  }

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
     * A test value. The platform integration's tests use it to prove two things: keys of
     * an adapter inside the shared tree are accepted, and they reach the adapter's
     * overlay with their type.
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
   * The dummy adapter's view of one task section. This is the MOST specific level.
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
