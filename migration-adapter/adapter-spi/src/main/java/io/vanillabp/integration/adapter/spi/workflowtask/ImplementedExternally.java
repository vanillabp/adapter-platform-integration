package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.regex.Pattern;

/**
 * The property which says that something other than this application serves a task:
 * <code>implemented-externally</code>, and the lines a message hands the developer to copy.
 * <p>
 * Every task of a claimed BPMN process needs a <code>&#64;WorkflowTask</code> method, or
 * this property set to <code>true</code>. Without either the startup ends. The core holds
 * the rule ({@link WorkflowTaskWiring#validateTaskWiring(String, String, String, java.util.Collection)}),
 * and an adapter with a refusal of its own about a task nobody serves names the same key
 * through {@link #howToMark}, so the developer reads one sentence on every BPMS.
 * <p>
 * A task is named in the key by its element id or by its task definition. Both are read,
 * and where both are written the element id wins. The element id of an element also covers
 * every listener on it.
 */
public final class ImplementedExternally {

  /**
   * The name of the property, the same at every level it may be written at.
   */
  public static final String PROPERTY = "implemented-externally";

  /**
   * A name which needs no protection in a property key: a map key made of these characters
   * reads the same on Spring Boot and on Quarkus.
   */
  private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z0-9_-]+");

  private ImplementedExternally() {
  }

  /**
   * The property key for one task, written as a line for <code>application.properties</code>.
   * <p>
   * A task name with a dot, a colon or anything else a property key cannot carry as it is has
   * to be protected, and the two platforms do it differently: Spring Boot keeps a map key in
   * brackets, Quarkus in quotes, and both read a colon as the end of the key unless it is
   * escaped. Such a name is answered with one line per platform.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param taskName The element id or the task definition
   * @return One line, or for a name which needs protection one line per platform, each under a
   *         comment naming the platform
   */
  public static String propertyLine(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskName) {

    final var prefix = "vanillabp.workflow-modules.%s.workflows.%s.tasks"
        .formatted(workflowModuleId, bpmnProcessId);
    if (PLAIN_KEY.matcher(taskName).matches()) {
      return "%s.%s.%s=true".formatted(prefix, taskName, PROPERTY);
    }
    final var escaped = taskName
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("=", "\\=")
        .replace(" ", "\\ ");
    return """
        # Spring Boot
        %s[%s].%s=true
        # Quarkus
        %s."%s".%s=true"""
        .formatted(prefix, escaped, PROPERTY, prefix, escaped, PROPERTY);

  }

  /**
   * The property key for a whole BPMN process, written as a line for
   * <code>application.properties</code>. For a process no <code>&#64;WorkflowService</code>
   * claims it says that the process belongs to somebody else: it is deployed with its file and
   * left alone otherwise. A process id which a property key cannot carry as it is gets one line
   * per platform, the same way {@link #propertyLine} does it for a task.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return One line, or one line per platform, each under a comment naming the platform
   */
  public static String processPropertyLine(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var prefix = "vanillabp.workflow-modules.%s.workflows".formatted(workflowModuleId);
    if (PLAIN_KEY.matcher(bpmnProcessId).matches()) {
      return "%s.%s.%s=true".formatted(prefix, bpmnProcessId, PROPERTY);
    }
    final var escaped = bpmnProcessId
        .replace("\\", "\\\\")
        .replace(":", "\\:")
        .replace("=", "\\=")
        .replace(" ", "\\ ");
    return """
        # Spring Boot
        %s[%s].%s=true
        # Quarkus
        %s."%s".%s=true"""
        .formatted(prefix, escaped, PROPERTY, prefix, escaped, PROPERTY);

  }

  /**
   * The name a message offers for marking one task. For a listener it is the task
   * definition, because the element id would mark the element and every other listener on
   * it as well. For anything else it is the element id, which is where the configuration of
   * a task is going.
   *
   * @param task The task
   * @return The name to write in the key
   */
  public static String nameToMark(
      final BpmnTaskSpec task) {

    if ((task.listener() || (task.activityId() == null)) && (task.taskDefinition() != null)) {
      return task.taskDefinition();
    }
    return task.activityId();

  }

  /**
   * The sentence a message about a task nobody serves ends with: how to say that something
   * else serves it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param task The task
   * @return The sentence, the property line included
   */
  public static String howToMark(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec task) {

    return "If something other than this application serves it, say so with this line:%n%s"
        .formatted(propertyLine(workflowModuleId, bpmnProcessId, nameToMark(task)));

  }

}
