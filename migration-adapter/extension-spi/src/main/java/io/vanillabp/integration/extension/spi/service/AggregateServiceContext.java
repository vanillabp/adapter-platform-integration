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
   * The aggregate class this service is built for. VanillaBP asks the factory once per
   * aggregate class of the application, and this is what the application named as the type
   * argument when it injected the service.
   *
   * @return The workflow-aggregate class the service is built for
   */
  Class<?> getWorkflowAggregateClass();

  /**
   * The workflow module of the aggregate's primary BPMN process - the other half of the
   * pair which names that process.
   *
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
   * Reads the ID out of an aggregate, through the persistence VanillaBP resolved for it -
   * so a service does not have to know which attribute of the class holds it.
   *
   * @param workflowAggregate The aggregate
   * @return Its ID, in the aggregate's own ID type
   */
  Object getWorkflowAggregateId(
      Object workflowAggregate);

  /**
   * Loads an aggregate through the persistence VanillaBP resolved for it. Whatever is
   * passed is taken through the aggregate's own ID type first, so an ID a service read out
   * of a record of its own works as well as one from
   * {@link #getWorkflowAggregateId(Object)}.
   *
   * @param workflowAggregateId The ID of a workflow aggregate, in its own type or
   *          serialized
   * @return The aggregate, or <code>null</code> if the store holds none of that ID
   */
  Object loadWorkflowAggregate(
      Object workflowAggregateId);

  /**
   * Saves an aggregate through the persistence VanillaBP resolved for it.
   *
   * @param workflowAggregate The aggregate to save
   * @return The attached aggregate
   */
  Object saveWorkflowAggregate(
      Object workflowAggregate);

  /**
   * The handler methods of the application, so a service can ask whether one exists and
   * invoke it. An extension reaches its own contracts through this rather than looking for
   * the annotated methods itself.
   *
   * @return The handler methods of the application, for the contracts this extension
   *         registered
   */
  ExtensionHandlers getHandlers();

  /**
   * The election, so a service asks which BPMS holds a workflow instead of addressing the
   * first-priority adapter. During a migration the two are not the same.
   *
   * @return The election, to find the BPMS holding a workflow of this aggregate
   */
  WorkflowElection getElection();

}
