package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.Map;

import io.vanillabp.spi.service.TaskEvent;

/**
 * All information a BPMS adapter supplies when a BPMN task is to be processed by a
 * <code>&#64;WorkflowTask</code> annotated method. The adapter builds one context per
 * task invocation (e.g. per Camunda 7 job execution or Camunda 8 job worker
 * delivery) and passes it to
 * {@link WorkflowTaskInvoker#invokeWorkflowTask(String, String, TaskInvocationContext)}.
 * The context is deliberately neutral: it carries only values, no BPMS types.
 */
public interface TaskInvocationContext {

  /**
   * The key used to resolve the <code>&#64;WorkflowTask</code> method: the BPMN
   * task's task definition (e.g. Camunda 8 job type, Camunda 7 topic/expression) or
   * the BPMN activity ID - handlers are registered under both
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code> respectively
   * <code>&#64;WorkflowTask(id = ...)</code>, defaulting to the method's name).
   *
   * @return The task definition or BPMN activity ID
   */
  String getTaskDefinition();

  /**
   * The workflow aggregate's ID in serialized form (the same String representation
   * used by the phase-two outbox, e.g. the Camunda 7 business key or the Camunda 8
   * aggregate-ID process variable). The core converts it back to the aggregate's ID
   * type and loads the aggregate.
   *
   * @return The serialized workflow-aggregate ID
   */
  String getWorkflowAggregateId();

  /**
   * The <code>id</code> attribute of the BPMN element being delivered, what a modeller
   * wrote on it. The Camunda 7 activity id, the Camunda 8 element id, whatever the BPMS
   * calls the element of the model.
   * <p>
   * {@link #getTaskDefinition()} carries the same text only where the BPMN task names no
   * task definition; a Camunda 8 job type or a Camunda 7 topic is reported there otherwise.
   * So the two are different answers, and the core needs both to find the method serving
   * the delivery: a method is wired by one of them
   * (<code>&#64;WorkflowTask(taskDefinition = ...)</code> respectively
   * <code>&#64;WorkflowTask(id = ...)</code>), and an application which wires by the element
   * id is reached through this value alone.
   * <p>
   * The core also writes it into the delivery record
   * ({@link io.vanillabp.integration.spi.TaskDelivery#bpmnElementId()}), where it is what an
   * extension addresses a task in the model by.
   * <p>
   * The default is <code>null</code>, which means "this adapter does not name one". Such an
   * adapter serves a method wired by the element id only where its task definition IS that
   * id, and its delivery records carry no element id.
   *
   * @return The BPMN element's id or <code>null</code>
   */
  default String getBpmnElementId() {

    return null;

  }

  /**
   * The BPMS' own id of the workflow this task belongs to: the process instance key of
   * Camunda 8, the process instance id of an embedded engine, whatever the BPMS talks about
   * a running instance in.
   * <p>
   * VanillaBP addresses a workflow by its workflow aggregate and never by this value, so
   * nothing here depends on it. It travels into the delivery record
   * ({@link io.vanillabp.integration.spi.TaskDelivery#workflowId()}) for whoever looks at the
   * BPMS beside VanillaBP: an operator searching the engine's own cockpit, or an extension
   * linking a task to the tooling of that BPMS.
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
   *
   * @param name The name of the local variable
   * @return The value or <code>null</code> if not present
   */
  default Object getTaskParameter(
      final String name) {

    return null;

  }

  /**
   * The multi-instance context(s) the task executes in, keyed by the name of the
   * multi-instance element and ordered from the outermost to the innermost
   * execution. Empty if the task is not part of a multi-instance execution.
   * <p>
   * Adapters have to supply an ORDER-PRESERVING map (e.g.
   * {@link java.util.LinkedHashMap}).
   *
   * @return The multi-instance contexts, outermost first
   */
  default Map<String, MultiInstanceValue> getMultiInstances() {

    return Map.of();

  }

  /**
   * The version of the deployed BPMN process definition this task belongs to, as the
   * BPMS counts it (Camunda 7 and Camunda 8 count integers upwards per BPMN process
   * id) - NOT a version the application invented. It is matched against
   * <code>&#64;WorkflowTask(version = ...)</code>, and a specification naming a
   * version TAG is resolved using the
   * {@link io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog} the
   * adapter registered.
   * <p>
   * <code>null</code> is the answer of an adapter whose BPMS cannot tell. Such a
   * delivery is served by a method whose version is <code>*</code> (the default), and
   * an application not using the attribute is therefore unaffected. A method naming
   * versions is NOT called: whether the version lies within its range cannot be
   * answered, and VanillaBP does not decide it by the order the methods happen to be
   * reflected in.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the handler has to run within the transaction already active on the
   * calling thread (embedded BPMS sharing the application's transaction, e.g.
   * Camunda 7 on the default datasource) instead of a new transaction opened by the
   * core (remote BPMS delivering tasks on worker threads, e.g. Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }


  /**
   * The ID of the adapter delivering this task. A delivery PROVES that this BPMS holds
   * the workflow, which is why VanillaBP records the association: the next operation
   * on that workflow probes the recorded adapter first, and an eventually consistent
   * BPMS which does not report the workflow yet gets a second look instead of an
   * immediate failure (see {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing - an adapter written
   * before this existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }

  /**
   * What identifies THIS delivery across redeliveries of the same task: the Camunda 8
   * job key, the ID of the task instance a remote engine reports, whatever the BPMS
   * uses to talk about this piece of work. A BPMS repeating a delivery has to yield
   * the SAME value, a genuinely new task instance (the next loop iteration, the next
   * multi-instance element, a retry AFTER the task was completed) a different one.
   * <p>
   * The core uses it to remember which deliveries it processed
   * ({@link io.vanillabp.integration.spi.TaskDeliveryLog}): a repeated delivery is
   * answered with the recorded outcome instead of running the
   * <code>&#64;WorkflowTask</code> method again.
   * <p>
   * The default is <code>null</code>, which means "this adapter cannot tell": the
   * handler is invoked for every delivery, as it always was. An EMBEDDED BPMS
   * delivering within the application's transaction ({@link #runInCurrentTransaction()})
   * has no reason to report one - a redelivery there proves that nothing was
   * committed, so there is nothing to remember (see
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService#deliversTasksAtLeastOnce()}).
   * <p>
   * A value does NOT have to be unique beyond this BPMS: the core prefixes it with
   * the adapter ID, the workflow module, the BPMN process and the event, so the ID
   * only has to be unique within the delivering BPMS.
   * <p>
   * <strong>Not to be confused with {@link #getActivationId()}</strong>, which the next
   * reader will otherwise take for the same value under a second name. This one is the
   * identity of a DELIVERY and stays equal while the BPMS repeats itself; that one is
   * the identity of an ACTIVATION and differs between two activations of one element.
   * An adapter which cannot answer this one can still answer that one, and Camunda 7
   * shows both halves. On a datasource shared with the application the delivery runs in
   * the transaction the application commits, so a repetition proves that nothing was
   * written and there is no delivery to name; on a datasource of its own the engine
   * commits its job separately and names the delivery by the job it is running, which is
   * the same value while it retries and a different one for new work.
   *
   * @return The delivery's identity or <code>null</code>
   */
  default String getDeliveryId() {

    return null;

  }

  /**
   * What identifies the ACTIVATION of the BPMN element this delivery belongs to: the
   * Camunda 8 element instance key, the activity instance ID of an embedded engine,
   * whatever the BPMS calls the instance of the element which is running. Two
   * activations of one element - the next loop iteration, the second element of a
   * multi-instance activity - have to yield DIFFERENT values, and the value has to stay
   * the same for as long as one activation lasts.
   * <p>
   * It is explicitly NOT required to differ between redeliveries of ONE activation.
   * That is what tells it apart from {@link #getDeliveryId()}, whose contract is the
   * opposite: equal across redeliveries. A BPMS naming its deliveries after the element
   * instance answers one value for both, which is why the two were not told apart
   * before; a BPMS whose delivery is bound to the application's transaction reports no
   * delivery id and still knows its activity instance.
   * <p>
   * The core reads it while the handler runs
   * ({@link io.vanillabp.integration.spi.RunningActivation}) and puts it into the
   * idempotency key of a message correlation, so that multi-instance siblings of one
   * workflow aggregate stop sharing a key (see decision 23 in the repository's
   * DECISIONS.md). Nothing else uses it, and in particular the delivery log does not:
   * feeding this value to the log would make every redelivery look like new work.
   * <p>
   * The default is <code>null</code>, which means "this adapter cannot tell". Keys are
   * then derived the way they were before this existed, so an adapter written earlier
   * keeps working and its multi-instance siblings keep colliding.
   *
   * @return The activation's identity or <code>null</code>
   */
  default String getActivationId() {

    return null;

  }

  /**
   * Whether this delivery belongs to a workflow which was already running before the
   * version this boot deployed, so anything measured from the moment VanillaBP first
   * saw the task is a LOWER BOUND rather than the truth.
   *
   * <h4>What it is for</h4>
   *
   * The core writes down a task delivery when the handler runs, and the age of a task
   * left open by a {@code @TaskId} handler is measured from that moment
   * (<code>vanillabp.delivery.max-task-age</code>). For a task which was already open
   * when the application was stopped for an upgrade there is no such record: the first
   * redelivery afterwards writes one, and from then on the task reports an age counted
   * from the upgrade. The tasks with the largest real age are exactly the ones whose age
   * would be under-reported, which is the opposite of useful.
   *
   * <h4>Why the adapter answers it and not the core</h4>
   *
   * Only the adapter knows both halves without asking anybody: the version the
   * delivery's workflow runs on, and the version it deployed itself. The core would have
   * to be told what "older" means for this BPMS, which is exactly the knowledge an
   * adapter exists to hold.
   * <p>
   * The answer costs nothing. It is a comparison of two values the adapter already has,
   * never a question to the BPMS - a query per delivery to sharpen a diagnostic would be
   * the wrong trade, and a lower bound is worth more than an exact number nobody pays
   * for.
   *
   * <h4>What a wrong answer costs</h4>
   *
   * Nothing but precision, in one direction. Answering <code>false</code> for a workflow
   * which IS older leaves today's behaviour; answering <code>true</code> makes a message
   * say "at least" where it could have been exact. So the default is <code>false</code>,
   * which is what an adapter says when it cannot tell, and an adapter whose BPMS counts
   * no versions never has to think about this.
   * <p>
   * Note that a BPMS which does not produce a new version on an upgrade answers
   * <code>false</code> for a genuinely older workflow, and correctly so: where the
   * deployed model is the one those workflows run on, there is no boundary to report.
   *
   * @return <code>true</code> where this workflow predates the deployed version
   */
  default boolean predatesDeployedVersion() {

    return false;

  }


  /**
   * The business key the BPMS keeps for this workflow of its own accord, where the BPMS
   * has such a thing at all: the Camunda 7 business key, the business id of a
   * Camunda 8 cluster from 8.10 on, whatever a BPMS calls the one value an operator
   * recognizes an instance by in its own tooling.
   * <p>
   * VanillaBP names a workflow by its workflow aggregate and by nothing else, so a
   * business key is only ever a COPY of {@link #getWorkflowAggregateId()}. Where
   * VanillaBP starts the workflow it writes that copy itself. Where somebody else
   * started it, the two values can say different things about one instance, and then
   * the workflow carries two identities at once. The core refuses such a delivery
   * instead of picking one of them, and the BPMS does with the refusal what it does
   * with any failing task. Why that is a refusal, and why an empty answer contradicts
   * nothing, is decision 69 in the repository's DECISIONS.md.
   * <p>
   * The default is <code>null</code>, which means "this BPMS keeps no business key".
   * That is the whole answer for Camunda 8 up to 8.9 and for the Process-Engine-API. An
   * adapter whose BPMS keeps the aggregate's id IN its business key reports it here as
   * well: the value costs nothing to pass on and the comparison then simply holds.
   *
   * @return The BPMS' own business key of this workflow or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
