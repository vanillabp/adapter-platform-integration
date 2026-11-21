package io.vanillabp.integration.spi.workflowtask;

/**
 * What the core knows about the BPMN processes the <code>&#64;WorkflowService</code> classes
 * of the application declare: whether a class claims a process, the name of the ID property
 * of its workflow aggregate, and whether two processes work on the same workflow aggregate.
 * The core implements it.
 *
 * <h2>Who asks</h2>
 *
 * An extension asks it, and it asks only this interface. A BPMS adapter asks the same
 * methods through <code>WorkflowTaskWiring</code> of the adapter SPI, which extends this
 * interface. An extension never takes <code>WorkflowTaskWiring</code>: that type belongs to
 * the adapter SPI, and an extension depends on the extension SPI alone.
 * <p>
 * The question about a call activity,
 * {@link #workflowsShareTheWorkflowAggregate(String, String, String)}, is asked by adapters
 * and by extensions which follow a workflow into the processes it calls.
 *
 * <h2>How to get it</h2>
 *
 * Both platform integrations publish the core's object under this type as well. So ask for
 * a bean of this type like for any other bean: a constructor parameter on Spring Boot, an
 * injection point or a producer parameter on Quarkus. You may ask from the moment the
 * workflow services are registered, which is before the first <code>wireBpmn</code>.
 */
public interface WorkflowTaskClaims {

  /**
   * The name of the ID property of the workflow aggregate for the given BPMN process.
   * Remote BPMS without a business key use it. They store the ID of the aggregate as a
   * process variable named after the ID property. The start commands write it, and the
   * task workers read it back.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The ID property's name
   * @throws IllegalStateException If no workflow service serves the BPMN process
   *           (with a message which says what to do)
   */
  String resolveWorkflowAggregateIdName(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Whether a <code>&#64;WorkflowService</code> class of the application claims the given
   * BPMN process. The application is responsible for a claimed process. Its tasks need
   * methods, and a task which nothing serves stops the start. A process nobody claims was
   * deployed only because it shares a file with a claimed one. Apart from that, VanillaBP
   * does not touch it. An adapter does not change it, and an extension reports nothing
   * about it and adds nothing to it.
   * <p>
   * The default answers with {@link #resolveWorkflowAggregateIdName(String, String)}. Only a
   * claimed process has a workflow aggregate. So a name means claimed, and the exception
   * means not claimed. The persistence of a claimed process may have no name to give. Its
   * <code>getAggregateIdName</code> then throws an <code>UnsupportedOperationException</code>,
   * which an application on a BPMS with a business key is allowed to do. The default answers
   * <code>true</code> for that exception, because only a claimed process gets as far as its
   * persistence. The core answers from its registry instead. The reason: the name
   * comes from the persistence of the application, and an application on a BPMS with a
   * business key does not have to answer it. Adapters and extensions ask this, instead of
   * catching the exception themselves.
   * <p>
   * A process called by a call activity counts as claimed if a workflow service declares
   * it, for example as a <code>secondaryBpmnProcesses</code> entry of the workflow service
   * of the caller.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID, as the application declares it
   * @return Whether a workflow service claims the process
   */
  default boolean isClaimedByAWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId) {

    try {
      return resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId) != null;
    } catch (final IllegalStateException e) {
      return false;
    } catch (final UnsupportedOperationException e) {
      return true;
    }

  }

  /**
   * Whether two BPMN processes of one workflow module work on the SAME workflow aggregate.
   * The declaration says so: one class declares the process to be started as its
   * {@code bpmnProcess}, and the others as {@code secondaryBpmnProcesses}.
   * <p>
   * Adapters ask this about a call activity. A process called on the same aggregate
   * continues the same business case and has to reach the same aggregate. But a process
   * with an aggregate of its own must not get the identity of the caller. Camunda 7 needs
   * the answer, because it does not pass its business key to a called process by itself.
   * The business key carries the ID of the aggregate. An extension which follows a workflow
   * into a called process asks it for the same reason: only a process on the same aggregate
   * belongs to the same business case.
   * <p>
   * The default is <code>false</code>. The core answers this, and a test double of this
   * interface should not make up an answer which makes an adapter change a model.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param otherBpmnProcessId The BPMN process ID to compare with
   * @return Whether both processes serve the same workflow aggregate.
   *         <code>false</code> if either of them is unknown
   */
  default boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    return false;

  }

}
