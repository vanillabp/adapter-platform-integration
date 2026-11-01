package io.vanillabp.integration.adapter.spi;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reads the <code>message-time-to-live</code> of one adapter. The platform hands each adapter
 * one of these, and it answers from the four levels of the configuration (message &gt;
 * workflow &gt; workflow-module &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.messages.&lt;messageName&gt;.adapters.&lt;id&gt;.message-time-to-live
 * </pre>
 *
 * The keys, their order and the choice of the process are the same for every BPMS, so they
 * are decided here once. What the number means is not. A Camunda 8 cluster keeps a published
 * message for its time-to-live, and an embedded engine asks the outbox to try again for that
 * long. So the adapter decides what a missing value means and which values it refuses.
 */
@FunctionalInterface
public interface MessageTimeToLiveResolver {

  /**
   * The time-to-live configured for a message of one BPMN process.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID of the process the value is read for
   * @param messageName The BPMN message name, as the application wrote it (without any
   *          prefix a name-clash-avoidance mode adds)
   * @return The most specific configured time-to-live, or <code>null</code> where no level
   *         configures one
   */
  Duration configuredFor(
      String workflowModuleId,
      String bpmnProcessId,
      String messageName);

  /**
   * The time-to-live of a correlation, read for the process whose model waits for the
   * message.
   * <p>
   * A process service belongs to the process at the top, so a correlation names that
   * process. But the event which waits may sit in a called process. Whoever configures the
   * time-to-live of that message writes it under the called process
   * (<code>workflows.&lt;called process&gt;.messages.&lt;message&gt;</code>). A message may come
   * before anything waits for it, so no subscription can answer. The models can: see
   * {@link #processWaitingForTheMessage}.
   * <p>
   * The models are read only where the processes of the module would answer differently.
   * Most applications configure the time-to-live per adapter or per workflow module, or not at
   * all. Then every process gives the same answer, and reading a model would not change it.
   *
   * @param workflowModuleId The workflow module of the correlation
   * @param bpmnProcessId The plain BPMN process ID which the correlation names
   * @param messageName The message name as the application wrote it
   * @param processesOfTheModule The plain BPMN process IDs of the workflow module
   * @param processesWaitingForTheMessage Reads the models and answers with the plain IDs of
   *          the processes of the module whose model waits for the message. It is called at
   *          most once
   * @return The time-to-live, or <code>null</code> where no level configures one for that
   *         process
   */
  default Duration resolveFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final Collection<String> processesOfTheModule,
      final Supplier<? extends Collection<String>> processesWaitingForTheMessage) {

    final var ofTheCall = configuredFor(workflowModuleId, bpmnProcessId, messageName);
    final var everyProcessAgrees = processesOfTheModule
        .stream()
        .allMatch(process -> Objects.equals(ofTheCall, configuredFor(workflowModuleId, process, messageName)));
    if (everyProcessAgrees) {
      return ofTheCall;
    }
    return configuredFor(
        workflowModuleId,
        processWaitingForTheMessage(bpmnProcessId, processesWaitingForTheMessage.get()),
        messageName);

  }

  /**
   * The process a time-to-live is read for. It is the process of the call, if its own model
   * waits for the message. Otherwise it is the one other process of the workflow module whose
   * model does. If none does, or several others do, nothing tells them apart, and the process
   * of the call is the answer.
   *
   * @param bpmnProcessId The plain BPMN process ID which the correlation names
   * @param waiting The plain IDs of the processes whose model waits for the message
   * @return The plain ID of the process the time-to-live is read for
   */
  static String processWaitingForTheMessage(
      final String bpmnProcessId,
      final Collection<String> waiting) {

    final var distinct = List.copyOf(new java.util.LinkedHashSet<>(waiting));
    if (distinct.contains(bpmnProcessId) || (distinct.size() != 1)) {
      return bpmnProcessId;
    }
    return distinct.getFirst();

  }

}
