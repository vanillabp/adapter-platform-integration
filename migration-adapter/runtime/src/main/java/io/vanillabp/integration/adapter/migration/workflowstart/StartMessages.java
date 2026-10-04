package io.vanillabp.integration.adapter.migration.workflowstart;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses;

/**
 * The messages which start each deployed BPMN process, as the adapters reported them
 * while the application started, and the check of
 * <code>ProcessService#startWorkflowByMessage</code> which rests on them.
 * <p>
 * A process service starts its own process and no other. A message which starts another
 * process is refused before phase one, because the core would otherwise write down the
 * new workflow under the process of the caller. Why the check is made here and not left
 * to the BPMS is decision 109 in the repository's DECISIONS.md.
 * <p>
 * Three things decide how the check works:
 * <ul>
 * <li>The names are kept per adapter. During a migration two adapters may deploy one
 * process in two different versions, and the check asks the adapter which starts the
 * workflow.</li>
 * <li>The names come from the model the adapter deploys during this start. A message
 * always starts the newest version of a process, and the newest version this application
 * knows is the one it deploys.</li>
 * <li>The names are plain, as the application passes them. Where an adapter prefixes
 * identifiers to avoid name clashes, it strips the prefix before it reports, so both
 * sides of the comparison are the plain name.</li>
 * </ul>
 * An adapter which reports nothing for a process is not checked for it. That keeps an
 * adapter working which does not know about the report yet, and one which cannot read a
 * model at all. The start says so once per process.
 */
public class StartMessages {

  private static final Logger log = LoggerFactory.getLogger(StartMessages.class);

  private record ReportKey(
                           String adapterId,
                           String workflowModuleId,
                           String bpmnProcessId) {
  }

  private final Map<ReportKey, Set<String>> reported = new ConcurrentHashMap<>();

  /**
   * Which ids a model was deployed under. An id the application only declares has no
   * model this start could read, so nobody reports its messages, and saying so would be
   * noise about a process nobody starts any more.
   */
  private final DeclaredBpmnProcesses declaredProcesses;

  /**
   * Built by the workflow-task registry, once per application.
   *
   * @param declaredProcesses Which BPMN process ids the application declares without any
   *          adapter deploying them, or <code>null</code> where that is not known
   */
  public StartMessages(
      final DeclaredBpmnProcesses declaredProcesses) {

    this.declaredProcesses = declaredProcesses;

  }

  /**
   * Keeps the messages an adapter reported for one process. A second report of the same
   * adapter for the same process adds to the first.
   *
   * @param adapterId The adapter which deployed the process
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param messageNames The plain names of the messages which start the process
   */
  public void report(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<String> messageNames) {

    final var names = reported.computeIfAbsent(
        new ReportKey(adapterId, workflowModuleId, bpmnProcessId),
        key -> ConcurrentHashMap.newKeySet());
    if (messageNames != null) {
      messageNames
          .stream()
          .filter(java.util.Objects::nonNull)
          .forEach(names::add);
    }

  }

  /**
   * Refuses a message which does not start the process of the calling process service.
   * Called before phase one, so nothing was saved or planned when it throws.
   *
   * @param adapterId The adapter which would start the workflow
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID of the calling process service
   * @param messageName The message name the application passed
   * @throws IllegalArgumentException If the adapter reported the messages of that process
   *           and the given one is not among them
   */
  public void refuseAMessageWhichStartsAnotherProcess(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName) {

    final var names = reported.get(new ReportKey(adapterId, workflowModuleId, bpmnProcessId));
    if ((names == null) || names.contains(messageName)) {
      return;
    }
    final var whatStartsThisProcess = names.isEmpty()
        ? """
            This process has no message start event in the model adapter '%s' deployed. Start it \
            with ProcessService#startWorkflow, or use the ProcessService of the process the \
            message is meant to start."""
            .formatted(adapterId)
        : """
            The messages which start this process in the model adapter '%s' deployed are: %s. \
            Pass one of them, or use the ProcessService of the process the message is meant to \
            start."""
            .formatted(adapterId, describe(names));
    throw new IllegalArgumentException(
        """
            Message '%s' does not start BPMN process '%s' of workflow module '%s'! \
            ProcessService#startWorkflowByMessage starts the process of its own ProcessService and \
            no other. %s VanillaBP 1 started any process which knew the message."""
            .formatted(messageName, bpmnProcessId, workflowModuleId, whatStartsThisProcess));

  }

  /**
   * Says once, while the application starts, that a process is not checked because the
   * adapter which starts it reported no messages for it. A process the application only
   * declares is left out: no adapter deployed a model for it.
   *
   * @param adapterId The adapter which starts the workflows of that process
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void sayWhereMessagesAreNotChecked(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (reported.containsKey(new ReportKey(adapterId, workflowModuleId, bpmnProcessId))) {
      return;
    }
    if ((declaredProcesses != null) && declaredProcesses.isDeclaredWithoutDeployment(workflowModuleId, bpmnProcessId)) {
      return;
    }
    log
        .info(
            """
                Adapter '{}' does not say which messages start BPMN process '{}' of workflow module \
                '{}', so ProcessService#startWorkflowByMessage is not checked for it. A message \
                which starts another process starts that one, as in VanillaBP 1.""",
            adapterId,
            bpmnProcessId,
            workflowModuleId);

  }

  private static String describe(
      final Set<String> names) {

    return new TreeSet<>(names)
        .stream()
        .map("'%s'"::formatted)
        .collect(Collectors.joining(", "));

  }

}
