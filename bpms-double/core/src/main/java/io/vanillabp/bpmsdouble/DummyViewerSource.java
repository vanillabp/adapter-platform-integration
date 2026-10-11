package io.vanillabp.bpmsdouble;

import java.util.List;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;

/**
 * Test hook which steers the answers of the dummy adapter's viewer and history API.
 * With it, acceptance tests run the core's read path without a real BPMS: the election,
 * the namespacing of process definition ids per adapter, and the guiding errors.
 * Without such a bean the double knows no definitions, no BPMN XML and no history.
 * <p>
 * The ids used here are the adapter's OWN ids. The core puts the adapter id in front of
 * them before it hands them to the application.
 */
public interface DummyViewerSource {

  /**
   * The process definitions this double reports for one workflow. A test answers here to
   * run the core's read path without a BPMS. It shows which adapter is elected, and how
   * the core namespaces the ids of the elected one.
   *
   * @param adapterId The dummy adapter's ID (several instances may be configured)
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext The history context or <code>null</code>
   * @return The process definitions (adapter-native ids), an empty list for "the
   *         workflow is unknown to me" or <code>null</code> to let another source
   *         answer
   */
  List<ProcessDefinition> getProcessDefinitions(
      String adapterId,
      Object workflowAggregateId,
      String historyContext);

  /**
   * The BPMN XML this double holds for one of its own definitions. The double parses
   * nothing. So a test may return any text and check that it reaches the application
   * unchanged.
   *
   * @param adapterId The dummy adapter's ID
   * @param processDefinitionId The ADAPTER-NATIVE process definition id
   * @return The BPMN XML or <code>null</code> if unknown
   */
  String getBpmnXml(
      String adapterId,
      String processDefinitionId);

  /**
   * The execution history this double reports for one workflow. A test builds it by hand.
   * So the read path can be tested for a BPMS which records a history of elements, and
   * for one which records none.
   *
   * @param adapterId The dummy adapter's ID
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param historyContext The history context or <code>null</code>
   * @return The history (adapter-native process definition id) or
   *         <code>null</code> if unknown
   */
  WorkflowHistory getWorkflowHistory(
      String adapterId,
      Object workflowAggregateId,
      String historyContext);

}
