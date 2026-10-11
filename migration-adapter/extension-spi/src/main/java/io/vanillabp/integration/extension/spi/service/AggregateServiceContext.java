package io.vanillabp.integration.extension.spi.service;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;

/**
 * What an {@link AggregateServiceFactory} gets when VanillaBP asks it for the service of
 * one workflow-aggregate class. It says which aggregate and which workflow it is about.
 * It also gives the three things a service usually needs: the persistence of the
 * aggregate, the handler methods of the application, and the election.
 */
public interface AggregateServiceContext {

  /**
   * The aggregate class this service is built for. VanillaBP asks the factory once per
   * aggregate class of the application. This is the class the application named as the type
   * argument when it injected the service.
   *
   * @return The workflow-aggregate class the service is built for
   */
  Class<?> getWorkflowAggregateClass();

  /**
   * The workflow module of the aggregate's primary BPMN process. Together with the BPMN
   * process ID, it names that process.
   *
   * @return The workflow module the aggregate's primary BPMN process belongs to
   */
  String getWorkflowModuleId();

  /**
   * The BPMN process which the aggregate's <code>&#64;WorkflowService</code> declares as its
   * primary one. The injectable {@code ProcessService} of that aggregate addresses the same
   * process.
   *
   * @return The BPMN process ID
   */
  String getBpmnProcessId();

  /**
   * Reads the ID out of an aggregate, through the persistence VanillaBP found for it. So a
   * service does not have to know which attribute of the class holds it.
   *
   * @param workflowAggregate The aggregate
   * @return Its ID, in the aggregate's own ID type
   */
  Object getWorkflowAggregateId(
      Object workflowAggregate);

  /**
   * Loads an aggregate through the persistence VanillaBP found for it. Whatever is passed
   * is first turned into the aggregate's own ID type. So an ID which a service read out of
   * a record of its own works as well as one from
   * {@link #getWorkflowAggregateId(Object)}.
   *
   * @param workflowAggregateId The ID of a workflow aggregate, in its own type or
   *          serialized
   * @return The aggregate, or <code>null</code> if the store holds none of that ID
   */
  Object loadWorkflowAggregate(
      Object workflowAggregateId);

  /**
   * Saves an aggregate through the persistence VanillaBP found for it.
   *
   * @param workflowAggregate The aggregate to save
   * @return The attached aggregate
   */
  Object saveWorkflowAggregate(
      Object workflowAggregate);

  /**
   * The handler methods of the application, so a service can ask whether one exists and
   * invoke it. An extension reaches its own contracts through this. It does not look for
   * the annotated methods itself.
   *
   * @return The handler methods of the application, for the contracts this extension
   *         registered
   */
  ExtensionHandlers getHandlers();

  /**
   * The election, so a service can ask which BPMS holds a workflow. It should not just
   * address the adapter with the first priority. During a migration the two differ.
   *
   * @return The election, to find the BPMS holding a workflow of this aggregate
   */
  WorkflowElection getElection();

}
