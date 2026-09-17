package io.vanillabp.integration.extension.spi.service;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;

/**
 * What an {@link AggregateServiceFactory} is handed when VanillaBP asks it for the
 * service of one workflow-aggregate class: which aggregate and which workflow it is
 * about, and the three things a service typically needs - the aggregate's persistence,
 * the handler methods of the application and the election.
 */
public interface AggregateServiceContext {

  /**
   * @return The workflow-aggregate class the service is built for
   */
  Class<?> getWorkflowAggregateClass();

  /**
   * @return The workflow module the aggregate's primary BPMN process belongs to
   */
  String getWorkflowModuleId();

  /**
   * The BPMN process the aggregate's <code>&#64;WorkflowService</code> declares as its
   * primary one - the same process the injectable
   * {@code ProcessService} of that aggregate addresses.
   *
   * @return The BPMN process ID
   */
  String getBpmnProcessId();

  /**
   * @param workflowAggregate The aggregate
   * @return Its ID, in the aggregate's own ID type
   */
  Object getWorkflowAggregateId(
      Object workflowAggregate);

  /**
   * @param workflowAggregateId The ID of a workflow aggregate, in its own type or
   *          serialized
   * @return The aggregate, or <code>null</code> if the store holds none of that ID
   */
  Object loadWorkflowAggregate(
      Object workflowAggregateId);

  /**
   * Which state of the given aggregate is the current one, as the application's own
   * auditing names it. Ask for it while the transaction which changed the aggregate is
   * still open, and carry it in the phase-two call your extension plans there
   * (<code>PhaseTwoCall#askingForTheStateOfTheEvent</code>): the dispatch of that call
   * then reads the aggregate as it was at this moment instead of as it is then.
   * <p>
   * The answer is <code>null</code> where the application keeps no history of its
   * aggregates, which is the normal case. A call carrying <code>null</code> is a call
   * asking for nothing, so an extension may always ask and pass on what it gets.
   * <p>
   * The default answers <code>null</code>, so a context of somebody's own keeps working
   * unchanged.
   *
   * @param workflowAggregate The aggregate whose current state is to be named
   * @return The auditing id, or <code>null</code> where there is no auditing
   */
  default String getAuditingId(
      final Object workflowAggregate) {

    return null;

  }

  /**
   * The aggregate as it was when the given auditing id was the current one - what an
   * extension loads while it dispatches a call which asked for the state of its event.
   * <p>
   * An auditing id of <code>null</code>, and an application which keeps no history,
   * both give the aggregate as it is now. Where the old state is gone because the
   * auditing was cleaned up, the current one is answered and VanillaBP writes a warning
   * naming the aggregate and the id: a report carrying newer values is better than no
   * report.
   * <p>
   * The default ignores the auditing id, so a context of somebody's own keeps working
   * unchanged.
   *
   * @param workflowAggregateId The ID of a workflow aggregate, in its own type or
   *          serialized
   * @param auditingId The auditing id of the wanted state, or <code>null</code> for the
   *          state of this moment
   * @return The aggregate, or <code>null</code> if the store holds none of that ID
   */
  default Object loadWorkflowAggregate(
      final Object workflowAggregateId,
      final String auditingId) {

    return loadWorkflowAggregate(workflowAggregateId);

  }

  /**
   * Saves an aggregate through the persistence VanillaBP resolved for it.
   *
   * @param workflowAggregate The aggregate to save
   * @return The attached aggregate
   */
  Object saveWorkflowAggregate(
      Object workflowAggregate);

  /**
   * @return The handler methods of the application, for the contracts this extension
   *         registered
   */
  ExtensionHandlers getHandlers();

  /**
   * @return The election, to find the BPMS holding a workflow of this aggregate
   */
  WorkflowElection getElection();

}
