package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * Reads <code>implemented-externally</code>, the property with which an application says that
 * something other than itself serves a task, and says once per workflow module which lines of
 * it change nothing.
 * <p>
 * Where the line stands decides what it means. A line at the task itself says something about
 * that task, so a method next to it is a contradiction. A line above the task, at the workflow,
 * the workflow module or the application, covers whatever task below it has no method, and a
 * task with a method simply keeps its method.
 * <p>
 * The rule it serves is the wiring validation's: every task of a claimed process has a
 * <code>&#64;WorkflowTask</code> method or is marked with this property, see
 * {@link WorkflowTaskRegistry#validateTaskWiring(String, String, String, Collection)}. This
 * class answers the second half of that question and nothing more. The other meaning of a line
 * at the workflow, that a process nobody claims belongs to somebody else, is read by the
 * deployment before anything is deployed, from
 * {@link MigrationAdapterProperties#implementedExternallyAtTheWorkflow(String, String, String)}.
 * <p>
 * A list of names in a configuration file goes out of date without anybody noticing, which
 * was the reason against such a list. The warning about lines nothing needs is the answer to
 * that. Why the rule is what it is: decision 119 in the repository's DECISIONS.md.
 */
final class ImplementedExternallyCheck {

  /**
   * The configuration, or <code>null</code> for a registry built without one: then nothing is
   * marked, and nothing is warned about.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Where the warning goes.
   */
  private final StartupReport findings;

  /**
   * The task names the deployed models know, per workflow module and BPMN process: the element
   * ids and the task definitions every adapter handed over while it wired.
   */
  private final Map<String, Map<String, Set<String>>> knownTaskNames = new ConcurrentHashMap<>();

  /**
   * The claimed processes of each workflow module whose wiring was validated.
   */
  private final Map<String, Set<String>> claimedProcesses = new ConcurrentHashMap<>();

  /**
   * The processes of each workflow module where a line above the task covered at least one
   * task without a method.
   */
  private final Map<String, Set<String>> processesUsingAnInheritedMarking = new ConcurrentHashMap<>();

  /**
   * The check, reading the given configuration.
   *
   * @param properties The configuration, <code>null</code> to mark nothing
   * @param findings Where a line nothing needs is reported
   */
  ImplementedExternallyCheck(
      final MigrationAdapterProperties properties,
      final StartupReport findings) {

    this.properties = properties;
    this.findings = findings;

  }

  /**
   * Where a task's marking comes from.
   */
  enum Marking {

    /**
     * Nothing marks the task, or the most specific line says <code>false</code>.
     */
    NONE,

    /**
     * A line at the task itself says <code>true</code>, by element id or by task definition.
     */
    AT_THE_TASK,

    /**
     * A line above the task says <code>true</code>, and the task itself says nothing. It covers
     * the task only where the task has no method.
     */
    INHERITED

  }

  /**
   * Where the marking of this task comes from, for this adapter. The element id is asked
   * before the task definition, so a line for the element id wins where both are written. A
   * listener is the exception: its own task definition is what names it, and the line for its
   * element counts for it like a line from above.
   *
   * @param adapterId The adapter deploying the model, or <code>null</code>
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param task The task
   * @return The marking
   */
  Marking markingOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec task) {

    if (properties == null) {
      return Marking.NONE;
    }
    final var names = Stream
        .of(task.activityId(), task.taskDefinition())
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (task.listener()) {
      // a listener is named at the task level by its own task definition, and that line is
      // about this listener. A line for the element id is about the element and covers every
      // listener on it, so for a listener it is a line from above: it covers the listener only
      // where no method serves it, and a served listener on a marked user task is no conflict
      final var ofTheListener = task.taskDefinition() == null
          ? null
          : properties
              .implementedExternallyAtTheTask(workflowModuleId, bpmnProcessId, List.of(task.taskDefinition()),
                  adapterId);
      if (ofTheListener != null) {
        return ofTheListener
            ? Marking.AT_THE_TASK
            : Marking.NONE;
      }
    } else {
      final var atTheTask = properties.implementedExternallyAtTheTask(workflowModuleId, bpmnProcessId, names,
          adapterId);
      if (atTheTask != null) {
        return atTheTask
            ? Marking.AT_THE_TASK
            : Marking.NONE;
      }
    }
    return Boolean.TRUE.equals(properties.implementedExternally(workflowModuleId, bpmnProcessId, names, adapterId))
        ? Marking.INHERITED
        : Marking.NONE;

  }

  /**
   * Whether the application marked this task as served by something else, for this adapter,
   * at the task or above it.
   *
   * @param adapterId The adapter deploying the model, or <code>null</code>
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param task The task
   * @return Whether the most specific line about the task says <code>true</code>
   */
  boolean isMarked(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnTaskSpec task) {

    return markingOf(adapterId, workflowModuleId, bpmnProcessId, task) != Marking.NONE;

  }

  /**
   * Remembers that a claimed process was wired, which is what a line above its tasks is held
   * against.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   */
  void rememberClaimed(
      final String workflowModuleId,
      final String bpmnProcessId) {

    claimedProcesses
        .computeIfAbsent(workflowModuleId, module -> ConcurrentHashMap.newKeySet())
        .add(bpmnProcessId);

  }

  /**
   * Remembers that a line above the task covered a task without a method of this process.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   */
  void rememberAnInheritedMarkingWasUsed(
      final String workflowModuleId,
      final String bpmnProcessId) {

    processesUsingAnInheritedMarking
        .computeIfAbsent(workflowModuleId, module -> ConcurrentHashMap.newKeySet())
        .add(bpmnProcessId);

  }

  /**
   * Remembers the names of the tasks one adapter wired for a process, claimed or not.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process
   * @param tasks The tasks of the deployed model
   */
  void rememberTasks(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<BpmnTaskSpec> tasks) {

    final var names = knownTaskNames
        .computeIfAbsent(workflowModuleId, module -> new ConcurrentHashMap<>())
        .computeIfAbsent(bpmnProcessId, process -> ConcurrentHashMap.newKeySet());
    tasks
        .stream()
        .flatMap(task -> Stream.of(task.activityId(), task.taskDefinition()))
        .filter(Objects::nonNull)
        .forEach(names::add);

  }

  /**
   * Warns about every task-level line of <code>implemented-externally</code> of the workflow
   * module which names a task no deployed model of its process has, by element id or by task
   * definition. Asked once the module is deployed, when every adapter handed over its models,
   * so a task only the model of the second adapter of a migration has is not reported.
   *
   * @param workflowModuleId The workflow module
   */
  void warnAboutLinesNoModelNeeds(
      final String workflowModuleId) {

    if ((properties == null) || (findings == null)) {
      return;
    }
    final var module = properties
        .getWorkflowModules()
        .get(workflowModuleId);
    if (module == null) {
      return;
    }
    final var known = knownTaskNames.getOrDefault(workflowModuleId, Map.of());
    module
        .getWorkflows()
        .keySet()
        .stream()
        .sorted()
        .forEach(bpmnProcessId -> {
          final var namesOfTheModels = known.get(bpmnProcessId);
          final var unknown = properties
              .tasksMarkedImplementedExternally(workflowModuleId, bpmnProcessId)
              .stream()
              .filter(name -> (namesOfTheModels == null) || !namesOfTheModels.contains(name))
              .toList();
          if (unknown.isEmpty()) {
            return;
          }
          findings
              .warn(
                  StartupTopic.CONFIGURATION,
                  "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
                  messageAbout(workflowModuleId, bpmnProcessId, unknown, namesOfTheModels != null));
        });
    warnAboutLinesAboveTheTaskNothingNeeds(workflowModuleId);

  }

  /**
   * Warns about a line above the task, at a workflow or at the workflow module, which covers no
   * task without a method of the claimed processes this start wired. Such a line changes
   * nothing: every task below it has its method, and the method wins.
   * <p>
   * Only claimed processes are looked at. A line at a process nobody claims says that the
   * process belongs to somebody else, and that line is needed. A line at the application is not judged either, because it covers
   * several workflow modules and one module alone cannot tell whether it is needed.
   *
   * @param workflowModuleId The workflow module
   */
  private void warnAboutLinesAboveTheTaskNothingNeeds(
      final String workflowModuleId) {

    final var module = properties
        .getWorkflowModules()
        .get(workflowModuleId);
    final var claimed = claimedProcesses.getOrDefault(workflowModuleId, Set.of());
    final var using = processesUsingAnInheritedMarking.getOrDefault(workflowModuleId, Set.of());
    module
        .getWorkflows()
        .entrySet()
        .stream()
        .filter(workflow -> claimed.contains(workflow.getKey()))
        .filter(workflow -> !using.contains(workflow.getKey()))
        .filter(workflow -> saysTrue(workflow.getValue().getImplementedExternally(), workflow
            .getValue()
            .getAdapters()))
        .map(Map.Entry::getKey)
        .sorted()
        .forEach(bpmnProcessId -> findings
            .warn(
                StartupTopic.CONFIGURATION,
                "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
                """
                    The configuration says '%s' for BPMN process '%s' (workflow module '%s') as a \
                    whole, but every task of it has a @WorkflowTask method, and a method wins over \
                    a line above its task. The line changes nothing. Remove it, or write it at \
                    the task it is meant for."""
                    .formatted(ImplementedExternally.PROPERTY, bpmnProcessId, workflowModuleId)));
    if (!claimed.isEmpty() && using.isEmpty() && saysTrue(module.getImplementedExternally(), module.getAdapters())) {
      findings
          .warn(
              StartupTopic.CONFIGURATION,
              "workflow module '%s'".formatted(workflowModuleId),
              """
                  The configuration says '%s' for workflow module '%s' as a whole, but every task \
                  of its claimed processes has a @WorkflowTask method, and a method wins over a \
                  line above its task. The line changes nothing. Remove it, or write it at the \
                  task it is meant for."""
                  .formatted(ImplementedExternally.PROPERTY, workflowModuleId));
    }

  }

  /**
   * Whether a level says <code>true</code>, in general or for any adapter.
   */
  private static boolean saysTrue(
      final Boolean inGeneral,
      final Map<String, ? extends io.vanillabp.integration.adapter.migration.config.AdapterProperties> ofTheAdapters) {

    return Boolean.TRUE.equals(inGeneral) || ((ofTheAdapters != null) && ofTheAdapters
        .values()
        .stream()
        .anyMatch(adapter -> (adapter != null) && Boolean.TRUE.equals(adapter.getImplementedExternally())));

  }

  /**
   * The warning about lines nothing needs.
   */
  private static String messageAbout(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<String> unknown,
      final boolean theProcessWasDeployed) {

    final var where = theProcessWasDeployed
        ? "no task of the BPMN model this start deployed has such an element id or task definition"
        : "this start deployed no BPMN process '%s' in workflow module '%s'".formatted(bpmnProcessId, workflowModuleId);
    return """
        The configuration says '%s' of task(s) %s of BPMN process '%s' (workflow module '%s'), \
        but %s. The line(s) change nothing. Remove them, or correct the task name: a task is \
        named by its element id or by its task definition."""
        .formatted(
            ImplementedExternally.PROPERTY,
            unknown
                .stream()
                .map("'%s'"::formatted)
                .collect(Collectors.joining(", ")),
            bpmnProcessId,
            workflowModuleId,
            where);

  }

}
