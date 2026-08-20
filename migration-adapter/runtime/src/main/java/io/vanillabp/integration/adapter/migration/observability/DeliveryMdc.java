package io.vanillabp.integration.adapter.migration.observability;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.MDC;

/**
 * The logging context VanillaBP puts around everything it delivers to application
 * code: which BPMS, which workflow module, which BPMN process, which workflow, which
 * task, and what the BPMS calls this delivery. Every log line an application writes
 * inside a <code>&#64;WorkflowTask</code> method carries them, so a log search can
 * follow one workflow through a system without the application passing anything
 * around.
 * <p>
 * The keys are listed in the wiki together with a log pattern to paste. Camunda
 * documents MDC keys around its own handler as well, but its 8.9 client does not set
 * any; what VanillaBP puts here is more, and it is the same on every BPMS because it
 * is set in the core rather than in an adapter.
 * <p>
 * <b>VanillaBP touches its own keys and nothing else.</b> The previous values of
 * exactly these keys are remembered and restored on {@link #close()}, so a thread
 * which the application uses for other things as well - a Camunda 7 job-executor
 * thread, a request thread - looks afterwards the way it looked before. Use it as a
 * resource:
 *
 * <pre>
 * try (var mdc = DeliveryMdc.ofTaskDelivery(...)) {
 *   ...
 * }
 * </pre>
 */
public final class DeliveryMdc implements AutoCloseable {

  /**
   * The id of the adapter (and therefore the BPMS instance) the work came from.
   */
  public static final String ADAPTER = "vanillabp.adapter";

  /**
   * The id of the workflow module owning the BPMN process.
   */
  public static final String WORKFLOW_MODULE = "vanillabp.workflow.module";

  /**
   * The BPMN process id, as the application wrote it (without any prefixing a
   * name-clash-avoidance mode applies).
   */
  public static final String BPMN_PROCESS = "vanillabp.bpmn.process";

  /**
   * The id of the workflow aggregate, which is the id of the workflow itself - the
   * value to search a log by.
   */
  public static final String WORKFLOW_AGGREGATE_ID = "vanillabp.workflow.aggregate.id";

  /**
   * The task definition delivered (the BPMN task's task definition or its activity
   * id), absent outside a task delivery.
   */
  public static final String TASK_DEFINITION = "vanillabp.task.definition";

  /**
   * What the BPMS calls this delivery (the Camunda 8 job key, the task id of a
   * remote engine), absent where the BPMS does not name its deliveries. Two log
   * lines of one delivery share it, a redelivery repeats it.
   */
  public static final String DELIVERY_ID = "vanillabp.delivery.id";

  /**
   * Every key VanillaBP sets - the list to build a log pattern from, and the list
   * restored when a scope ends.
   */
  public static final List<String> KEYS = List.of(
      ADAPTER,
      WORKFLOW_MODULE,
      BPMN_PROCESS,
      WORKFLOW_AGGREGATE_ID,
      TASK_DEFINITION,
      DELIVERY_ID);

  private final Map<String, String> restore;

  private DeliveryMdc(
      final Map<String, String> values) {

    this.restore = new HashMap<>(KEYS.size());
    KEYS.forEach(key -> restore.put(key, MDC.get(key)));
    values.forEach(DeliveryMdc::put);

  }

  /**
   * The context of a task delivered to a <code>&#64;WorkflowTask</code> method.
   *
   * @param adapterId The id of the delivering adapter
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process
   * @param workflowAggregateId The workflow aggregate's id in serialized form
   * @param taskDefinition The task definition delivered
   * @param deliveryId What the BPMS calls this delivery
   * @return The scope, to be closed when the delivery is done
   */
  public static DeliveryMdc ofTaskDelivery(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String taskDefinition,
      final String deliveryId) {

    final var values = new HashMap<String, String>();
    values.put(ADAPTER, adapterId);
    values.put(WORKFLOW_MODULE, workflowModuleId);
    values.put(BPMN_PROCESS, bpmnProcessId);
    values.put(WORKFLOW_AGGREGATE_ID, workflowAggregateId);
    values.put(TASK_DEFINITION, taskDefinition);
    values.put(DELIVERY_ID, deliveryId);
    return new DeliveryMdc(values);

  }

  /**
   * The context of a phase-two call dispatched out of the transaction outbox. It
   * knows no task and no delivery of a BPMS: the entry was written by this
   * application, and what the BPMS answers to it is exactly what an operator is
   * looking for when a connection is broken.
   *
   * @param adapterId The id of the adapter the call goes to, if the entry names one
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process
   * @param workflowAggregateId The workflow aggregate's id in serialized form
   * @return The scope, to be closed when the dispatch is done
   */
  public static DeliveryMdc ofPhaseTwoDispatch(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var values = new HashMap<String, String>();
    values.put(ADAPTER, adapterId);
    values.put(WORKFLOW_MODULE, workflowModuleId);
    values.put(BPMN_PROCESS, bpmnProcessId);
    values.put(WORKFLOW_AGGREGATE_ID, workflowAggregateId);
    values.put(TASK_DEFINITION, null);
    values.put(DELIVERY_ID, null);
    return new DeliveryMdc(values);

  }

  private static void put(
      final String key,
      final String value) {

    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }

  }

  @Override
  public void close() {

    restore.forEach(DeliveryMdc::put);

  }

}
