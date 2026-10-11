package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Map;

import io.vanillabp.spi.service.TaskEvent;

/**
 * All information a BPMS adapter supplies when a <code>&#64;WorkflowTask</code> method is to
 * process a BPMN task. The adapter builds one context per task invocation (e.g. per Camunda
 * 7 job execution or per Camunda 8 job worker delivery) and passes it to
 * {@link WorkflowTaskInvoker#invokeWorkflowTask(String, String, TaskInvocationContext)}.
 * The context is neutral on purpose. It carries only values, no BPMS types.
 */
public interface TaskInvocationContext {

  /**
   * The key which finds the <code>&#64;WorkflowTask</code> method. It is the task definition
   * of the BPMN task (e.g. Camunda 8 job type, Camunda 7 topic/expression) or the BPMN
   * activity ID. Handlers are registered under both
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code> and
   * <code>&#64;WorkflowTask(id = ...)</code>, both default to the name of the method).
   *
   * @return The task definition or BPMN activity ID
   */
  String getTaskDefinition();

  /**
   * The ID of the workflow aggregate in serialized form. It is the same String which the
   * phase-two outbox uses, e.g. the Camunda 7 business key or the Camunda 8 process
   * variable with the aggregate ID. The core converts it back into the ID type of the
   * aggregate and loads the aggregate.
   *
   * @return The serialized workflow-aggregate ID
   */
  String getWorkflowAggregateId();

  /**
   * The <code>id</code> attribute of the delivered BPMN element, as a modeller wrote it. It
   * is the Camunda 7 activity id, the Camunda 8 element id, or whatever the BPMS calls the
   * element of the model.
   * <p>
   * {@link #getTaskDefinition()} carries the same text only if the BPMN task names no task
   * definition. Otherwise it carries a Camunda 8 job type or a Camunda 7 topic. So the two
   * are different answers, and the core needs both to find the method which serves the
   * delivery. A method is wired by one of them
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code> or
   * <code>&#64;WorkflowTask(id = ...)</code>). An application which wires by the element id
   * is reached only through this value.
   * <p>
   * The core also writes it into the delivery record
   * ({@link io.vanillabp.integration.spi.TaskDelivery#bpmnElementId()}). An extension uses it
   * there to address a task in the model.
   * <p>
   * The default is <code>null</code>, which means "this adapter does not name one". Such an
   * adapter serves a method wired by the element id only if its task definition IS that id.
   * Its delivery records carry no element id.
   *
   * @return The BPMN element's id or <code>null</code>
   */
  default String getBpmnElementId() {

    return null;

  }

  /**
   * The BPMS' own id of the workflow this task belongs to. It is the process instance key of
   * Camunda 8, the process instance id of an embedded engine, or whatever id the BPMS uses
   * for a running instance.
   * <p>
   * VanillaBP addresses a workflow by its workflow aggregate and never by this value. So
   * nothing here depends on it. It goes into the delivery record
   * ({@link io.vanillabp.integration.spi.TaskDelivery#workflowId()}) for whoever looks at the
   * BPMS next to VanillaBP: an operator who searches the engine's own cockpit, or an
   * extension which links a task to the tools of that BPMS.
   * <p>
   * The default is <code>null</code>, which means "this adapter does not name one". A BPMS
   * whose instances have no id of their own answers nothing and loses nothing.
   *
   * @return The workflow's id in the BPMS or <code>null</code>
   */
  default String getWorkflowId() {

    return null;

  }

  /**
   * The BPMS-side ID of this task instance, passed to parameters annotated with
   * <code>&#64;TaskId</code> and used to complete the task asynchronously.
   * <code>null</code> if the BPMS does not support asynchronous completion.
   *
   * @return The task instance's ID or <code>null</code>
   */
  default String getTaskId() {

    return null;

  }

  /**
   * Which kind of task {@link #getTaskId()} is the id of: a task the application works on,
   * or a user task a person works on.
   * <p>
   * The ids of the two kinds have namespaces of their own. If a BPMS is asked for a job
   * under the key of a user task, it answers "not found", although the task is alive.
   * Applications really make this mistake: they call {@code completeTask} with the id of a
   * user task. The answer they got named every possible cause except the real one. So the
   * kind goes into the delivery record
   * ({@link io.vanillabp.integration.spi.TaskDelivery#taskKind()}). VanillaBP reads it back
   * when no BPMS knows the id a caller named.
   * <p>
   * The kind is reported, not derived. It could be read from the task definition, because a
   * user task of a Camunda 8 cluster runs under a job type of VanillaBP's own. But that rule
   * belongs to one adapter and changes with every rename of that job type, while the record
   * belongs to the platform. The delivery knows the kind without asking anybody, so it says
   * it.
   * <p>
   * The default is <code>null</code>, which means "this adapter does not say". The record of
   * such a delivery names no kind, and it is left out of the answer. An adapter written
   * before this keeps working that way.
   *
   * @return The kind of task or <code>null</code>
   */
  default TaskKind getTaskKind() {

    return null;

  }

  /**
   * The event being processed. BPMS adapters deliver {@link TaskEvent.Event#CREATED}
   * when a task is to be processed; {@link TaskEvent.Event#CANCELED} arrives with
   * the complete/cancel feature.
   *
   * @return The task event
   */
  default TaskEvent.Event getTaskEvent() {

    return TaskEvent.Event.CREATED;

  }

  /**
   * The value of a local variable mapped in the BPMN (input mapping), passed to
   * parameters annotated with <code>&#64;TaskParam</code>.
   * <p>
   * These are values the MODEL produced, never values of the workflow aggregate. The names
   * asked for are the ones
   * {@link WorkflowTaskWiring#taskParameterNames(String, String, String)} answered while the
   * module was wired. They are plain names: a name is what the modeller wrote, and no scope
   * is added to it. Return the value the way the BPMS deserialized it, and convert nothing.
   * The core converts it into the type the handler declared. So one handler does not get
   * different values on different BPMS.
   *
   * @param name The name of the local variable
   * @return The value or <code>null</code> if not present
   */
  default Object getTaskParameter(
      final String name) {

    return null;

  }

  /**
   * The multi-instance contexts the task runs in. The key is the name of the
   * multi-instance element. The order goes from the outermost to the innermost
   * execution. Empty if the task is not part of a multi-instance execution.
   * <p>
   * Adapters have to supply a map which keeps its ORDER (e.g.
   * {@link java.util.LinkedHashMap}).
   *
   * @return The multi-instance contexts, outermost first
   */
  default Map<String, MultiInstanceValue> getMultiInstances() {

    return Map.of();

  }

  /**
   * The version of the deployed BPMN process definition this task belongs to, as the
   * BPMS counts it. Camunda 7 and Camunda 8 count integers upwards per BPMN process
   * id. It is NOT a version the application made up. It is matched against
   * <code>&#64;WorkflowTask(version = ...)</code>. A range which names a version TAG is
   * resolved with the
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog} the
   * adapter registered.
   * <p>
   * An adapter whose BPMS cannot tell answers <code>null</code>. Such a delivery is
   * served by a method whose version is <code>*</code> (the default). So an application
   * which does not use the attribute sees no difference. A method which names versions
   * is NOT called. Nobody can say whether the version lies in its range, and VanillaBP
   * does not decide it by the random order in which reflection lists the methods.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the handler has to run in the transaction which is already active on the
   * calling thread. That is the case for an embedded BPMS which shares the transaction
   * of the application, e.g. Camunda 7 on the default datasource. Otherwise the core opens
   * a new transaction, as for a remote BPMS which delivers tasks on worker threads, e.g.
   * Camunda 8.
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }


  /**
   * The ID of the adapter which delivers this task. A delivery PROVES that this BPMS
   * holds the workflow, so VanillaBP records which adapter it was. The next operation on
   * that workflow asks the recorded adapter first. Some BPMS are only eventually
   * consistent: they may not report the workflow yet. Such a BPMS gets a second look
   * instead of an immediate failure (see {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing. An adapter written before
   * this existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }

  /**
   * What identifies THIS delivery when the same task is delivered again. It is the
   * Camunda 8 job key, the ID of the task instance a remote engine reports, or whatever
   * the BPMS uses for this piece of work. If a BPMS repeats a delivery, it has to give the
   * SAME value. A really new task instance gives a different one: the next loop iteration,
   * the next multi-instance element, a retry AFTER the task was completed.
   * <p>
   * The core uses it to remember which deliveries it processed
   * ({@link io.vanillabp.integration.spi.TaskDeliveryLog}). A repeated delivery gets the
   * recorded outcome, and the <code>&#64;WorkflowTask</code> method does not run again.
   * <p>
   * The default is <code>null</code>, which means "this adapter cannot tell". Then the
   * handler is called for every delivery, as it always was. An EMBEDDED BPMS which
   * delivers in the application's transaction ({@link #runInCurrentTransaction()}) has no
   * reason to report one. A redelivery there proves that nothing was committed, so there
   * is nothing to remember (see
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#deliversTasksAtLeastOnce()}).
   * <p>
   * A value does NOT have to be unique beyond this BPMS. The core adds the adapter ID,
   * the workflow module, the BPMN process and the event in front of it. So the ID only
   * has to be unique within the delivering BPMS.
   * <p>
   * <strong>Do not mix it up with {@link #getActivationId()}</strong>. The next reader
   * might take them for the same value under two names. This one is the identity of a
   * DELIVERY, and it stays the same while the BPMS repeats itself. That one is the
   * identity of an ACTIVATION, and it differs between two activations of one element.
   * An adapter which cannot answer this one may still answer that one. Camunda 7 shows
   * both sides. On a datasource shared with the application, the delivery runs in the
   * transaction the application commits. So a repetition proves that nothing was written,
   * and there is no delivery to name. On a datasource of its own, the engine commits its
   * job separately and names the delivery by the job it runs. That value stays the same
   * while it retries, and it is a different one for new work.
   *
   * @return The delivery's identity or <code>null</code>
   */
  default String getDeliveryId() {

    return null;

  }

  /**
   * What identifies the ACTIVATION of the BPMN element this delivery belongs to. It is the
   * Camunda 8 element instance key, the activity instance ID of an embedded engine, or
   * whatever the BPMS calls the running instance of the element. Two activations of one
   * element have to give DIFFERENT values, for example the next loop iteration or the
   * second element of a multi-instance activity. The value has to stay the same as long
   * as one activation lasts.
   * <p>
   * It does NOT have to differ between redeliveries of ONE activation. That is the
   * difference to {@link #getDeliveryId()}, whose contract is the opposite: equal across
   * redeliveries. A BPMS which names its deliveries after the element instance gives one
   * value for both. That is why the two were not told apart before. A BPMS whose delivery
   * is bound to the application's transaction reports no delivery id, but still knows its
   * activity instance.
   * <p>
   * The core reads it while the handler runs
   * ({@link io.vanillabp.integration.spi.RunningActivation}). It puts it into the
   * idempotency key of a message correlation, so that multi-instance siblings of one
   * workflow aggregate no longer share a key (see decision 23 in the repository's
   * DECISIONS.md). Nothing else uses it, and the delivery log in particular does not. If
   * the log got this value, every redelivery would look like new work.
   * <p>
   * The default is <code>null</code>, which means "this adapter cannot tell". Then keys
   * are derived the way they were before this existed. So an adapter written earlier
   * keeps working, and its multi-instance siblings keep colliding.
   *
   * @return The activation's identity or <code>null</code>
   */
  default String getActivationId() {

    return null;

  }

  /**
   * Whether this delivery belongs to a workflow which was already running before the
   * version this start deployed. If so, anything measured from the moment VanillaBP first
   * saw the task is only a LOWER BOUND, not the real value.
   *
   * <h4>What it is for</h4>
   *
   * The core records a task delivery when the handler runs. The age of a task which a
   * {@code @TaskId} handler left open is measured from that moment
   * (<code>vanillabp.delivery.max-task-age</code>). Some tasks were already open when the
   * application was stopped for an upgrade. They have no such record. The first redelivery
   * afterwards writes one, and from then on the task reports an age counted from the
   * upgrade. So the tasks with the largest real age would report too small an age. That is
   * the opposite of useful.
   *
   * <h4>Why the adapter answers it and not the core</h4>
   *
   * Only the adapter knows both sides without asking anybody: the version the workflow of
   * the delivery runs on, and the version the adapter deployed itself. The core would have
   * to be told what "older" means for this BPMS. Holding that knowledge is the job of an
   * adapter.
   * <p>
   * The answer costs nothing. It compares two values the adapter already has, and it never
   * asks the BPMS. A query per delivery, just to sharpen a diagnostic, would cost too much.
   * A lower bound is worth more than an exact number nobody wants to pay for.
   *
   * <h4>What a wrong answer costs</h4>
   *
   * Only some precision, and in one direction only. If the answer is <code>false</code> for
   * a workflow which IS older, the behaviour stays as it is today. If the answer is
   * <code>true</code>, a message says "at least" where it could have been exact. So the
   * default is <code>false</code>. An adapter says that when it cannot tell. An adapter
   * whose BPMS counts no versions never has to think about this.
   * <p>
   * A BPMS which does not create a new version on an upgrade answers <code>false</code>
   * for a really older workflow, and that is correct. If those workflows run on the
   * deployed model, there is no boundary to report.
   *
   * @return <code>true</code> if this workflow is older than the deployed version
   */
  default boolean predatesDeployedVersion() {

    return false;

  }


  /**
   * The business key the BPMS keeps for this workflow by itself, if the BPMS has such a
   * thing at all. It is the Camunda 7 business key, the business id of a Camunda 8 cluster
   * from 8.9 on, or whatever a BPMS calls the one value by which an operator recognizes an
   * instance in its own tools.
   * <p>
   * VanillaBP names a workflow by its workflow aggregate and by nothing else. So a business
   * key is always just a COPY of {@link #getWorkflowAggregateId()}. If VanillaBP starts the
   * workflow, it writes that copy itself. If somebody else started it, the two values can
   * say different things about one instance. Then the workflow has two identities at once.
   * The core refuses such a delivery instead of picking one of them. The BPMS treats the
   * refusal like any failing task. Why that is a refusal, and why an empty answer
   * contradicts nothing: see decision 69 in the repository's DECISIONS.md.
   * <p>
   * The default is <code>null</code>, which means "this BPMS keeps no business key". That
   * is the whole answer for Camunda 8 up to 8.8 and for the Process-Engine-API. An adapter
   * whose BPMS keeps the id of the aggregate IN its business key reports it here as well.
   * Passing the value on costs nothing, and the comparison then simply holds.
   * <p>
   * A value which says nothing does not contradict. That holds for the other side too. If
   * {@link #getWorkflowAggregateId()} is missing, there is nothing to compare, and nothing
   * is refused. The case behind that rule is a workflow which an application takes over
   * after the upgrade from version 1 on Camunda 7. Version 1 wrote no process variables at
   * all. So such an instance carries its identity only in the business key.
   *
   * @return The BPMS' own business key of this workflow or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
