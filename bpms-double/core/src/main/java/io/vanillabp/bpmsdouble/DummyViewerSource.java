package io.vanillabp.bpmsdouble;

import java.util.List;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;

/**
 * Test hook steering the dummy adapter's viewer/history answers -
 * acceptance tests exercise the core's read path (election, per-adapter
 * namespacing of process definition ids, guiding errors) without a real BPMS.
 * Without such a bean the dummy knows no definitions, no BPMN XML and no history.
 * <p>
 * The ids used here are ADAPTER-NATIVE - the core namespaces them with the
 * adapter id before handing them to the application.
 */
public interface DummyViewerSource {

  /**
   * The process definitions this double reports for one workflow. A test answers here
   * to exercise the core's read path without a BPMS: which adapter is elected, and how
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
   * nothing, so a test may return any text and assert that it arrives at the
   * application unchanged.
   *
   * @param adapterId The dummy adapter's ID
   * @param processDefinitionId The ADAPTER-NATIVE process definition id
   * @return The BPMN XML or <code>null</code> if unknown
   */
  String getBpmnXml(
      String adapterId,
      String processDefinitionId);

  /**
   * The execution history this double reports for one workflow. A test builds it by
   * hand, which is how the read path is asserted both for a BPMS which records an
   * element history and for one which records none.
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
