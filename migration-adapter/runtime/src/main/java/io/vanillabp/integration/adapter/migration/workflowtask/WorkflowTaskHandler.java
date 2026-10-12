package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.HandlerContexts;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.TaskException;

/**
 * One method with <code>&#64;WorkflowTask</code> which is wired to a BPMN task: the target
 * bean, the method, and the parameter binders resolved at registration time.
 * {@link WorkflowTaskScanner} builds it. It is registered per workflow module and BPMN
 * process ID in the {@link WorkflowTaskRegistry}, and called by
 * {@link io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService#executeWorkflowTask}.
 */
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class WorkflowTaskHandler {

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<HandlerValueSource> parameterBinders;

  /**
   * The task definition to which this handler is wired, or <code>null</code> if it is wired
   * by activity ID only.
   */
  private final String taskDefinition;

  /**
   * The BPMN activity ID to which this handler is wired, or <code>null</code> if it is wired
   * by task definition only.
   */
  private final String activityId;

  /**
   * The process versions this method serves. The whole registry shares this object with
   * the other handler kinds and with the methods of an extension.
   */
  private final ServedVersions versions;

  /**
   * Whether the method declares a <code>&#64;TaskId</code> parameter. Then the task is
   * completed later, by somebody else, and MUST NOT be completed when the method
   * returns.
   */
  private final boolean asynchronousTask;

  /**
   * The lifecycle events to which the method subscribes. That is the union of the filters
   * of its <code>&#64;TaskEvent</code> parameters, or only CREATED without such a parameter.
   * {@link io.vanillabp.spi.service.TaskEvent.Event#ALL} subscribes to everything.
   */
  private final java.util.Set<io.vanillabp.spi.service.TaskEvent.Event> subscribedEvents;

  /**
   * The process variables which the method reads with <code>&#64;TaskParam</code>, sorted
   * and without duplicates. The core reports them to the adapters through
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#taskParameterNames}.
   * So a BPMS which delivers a payload of variables knows what to put into it.
   */
  private final List<String> taskParameters;

  /**
   * The iterations whose ITEM the method wants, one entry per
   * <code>&#64;MultiInstanceElement</code> parameter. A parameter which names its element
   * answers itself. One which uses a resolver bean asks that bean. The bean is looked up
   * when the answer is first needed, not while the method is scanned, because not all of
   * the application's beans exist at that point.
   */
  private final List<Supplier<java.util.Collection<String>>> multiInstanceElements;

  /**
   * What {@link #getMultiInstanceElementNames()} answered the first time. It is kept,
   * because the deployment asks once per BPMN element, and a resolver bean is looked up for
   * every question.
   */
  private volatile List<String> knownMultiInstanceElementNames;

  /**
   * Whether some BPMN task matched this handler during the wiring check. The check for
   * unwired methods per module reads it. A handler registered under several BPMN processes
   * through {@code secondaryBpmnProcesses} may match in only one of them, and that is
   * fine.
   */
  private volatile boolean wired = false;

  WorkflowTaskHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> parameterBinders,
      final String taskDefinition,
      final String activityId,
      final ServedVersions versions,
      final boolean asynchronousTask,
      final java.util.Set<io.vanillabp.spi.service.TaskEvent.Event> subscribedEvents,
      final List<String> taskParameters,
      final List<Supplier<java.util.Collection<String>>> multiInstanceElements) {

    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.parameterBinders = parameterBinders;
    this.taskDefinition = taskDefinition;
    this.activityId = activityId;
    this.versions = versions;
    this.asynchronousTask = asynchronousTask;
    this.subscribedEvents = subscribedEvents;
    this.taskParameters = taskParameters;
    this.multiInstanceElements = multiInstanceElements;

  }

  /**
   * @return The process variables the method reads with <code>&#64;TaskParam</code>
   */
  List<String> getTaskParameters() {

    return taskParameters;

  }

  /**
   * The BPMN element ids for which the method wants the item of an iteration, sorted and
   * without duplicates. The core reports them to the adapters through
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#multiInstanceElementNames}.
   * So an adapter which reads its own BPMN can say that a model hands over no such item.
   *
   * @return The element ids, empty where the method declares no
   *         <code>&#64;MultiInstanceElement</code>
   */
  List<String> getMultiInstanceElementNames() {

    var known = knownMultiInstanceElementNames;
    if (known == null) {
      known = multiInstanceElements
          .stream()
          .map(Supplier::get)
          .flatMap(java.util.Collection::stream)
          .filter(java.util.Objects::nonNull)
          .distinct()
          .sorted()
          .toList();
      knownMultiInstanceElementNames = known;
    }
    return known;

  }

  /**
   * Whether the method subscribes to the given lifecycle event. The core skips other
   * deliveries without calling the method, for example CANCELED to a method without a
   * <code>&#64;TaskEvent</code> parameter.
   *
   * @param event The delivered event
   * @return Whether to invoke the method
   */
  public boolean acceptsEvent(
      final io.vanillabp.spi.service.TaskEvent.Event event) {

    return subscribedEvents.contains(io.vanillabp.spi.service.TaskEvent.Event.ALL) || subscribedEvents.contains(event);

  }

  /**
   * One of the two keys by which this handler can be reached: what the BPMS subscribed to.
   * <p>
   * A delivery is routed by both keys, because the boot accepts both (decision 67 in the
   * repository's DECISIONS.md).
   *
   * @return The task definition, or <code>null</code> where this handler is wired by
   *         activity ID only
   */
  public String getTaskDefinition() {

    return taskDefinition;

  }

  /**
   * The other of the two keys: the element of the model, which
   * <code>&#64;WorkflowTask(id = ...)</code> names.
   *
   * @return The activity ID, or <code>null</code> where this handler is wired by task
   *         definition only
   */
  public String getActivityId() {

    return activityId;

  }

  /**
   * Whether completing the task is somebody else's job.
   * <p>
   * The method took a <code>&#64;TaskId</code>, so it keeps that id and completes the task
   * later. Completing the task when the method returns would end a task on which the
   * application still works.
   *
   * @return Whether the task stays open after the method returned
   */
  public boolean isAsynchronousTask() {

    return asynchronousTask;

  }

  /**
   * Says that a BPMN element of the deployed model matched this handler.
   * <p>
   * It is called while the wiring is checked. So the check for methods which nothing ever
   * calls can tell a method which the model dropped from one which simply belongs to another
   * of the BPMN processes its class declares.
   */
  public void markWired() {

    wired = true;

  }

  /**
   * Whether any deployed BPMN element matched this handler during this boot.
   *
   * @return Whether something wired it. <code>false</code> for a method which no model
   *         names
   */
  public boolean isWired() {

    return wired;

  }

  /**
   * The method, named so that a developer can find it: the class it was declared in, and its
   * name. Every message about this handler starts with it.
   *
   * @return The class name and the method name
   */
  public String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * The keys by which this handler is wired, for guiding messages.
   *
   * @return What a model has to name to reach this method
   */
  public String describeWiring() {

    if ((taskDefinition != null) && (activityId != null)) {
      return "task definition '%s' / activity ID '%s'".formatted(taskDefinition, activityId);
    }
    if (taskDefinition != null) {
      return "task definition '%s'".formatted(taskDefinition);
    }
    return "activity ID '%s'".formatted(activityId);

  }

  /**
   * The version specifications this method names, for messages about a method which serves
   * no version the BPMS holds.
   *
   * @return The specifications, comma separated
   */
  String describeVersions() {

    return versions.describe();

  }

  /**
   * The version specifications and, where the method named none itself, the declaration
   * they came from. Every message about an ambiguity, or about a method which serves
   * nothing, uses this. A complaint about a range which the reader cannot see at the method
   * looks like a defect of VanillaBP.
   *
   * @return The specifications and their origin
   */
  String describeVersionsWithOrigin() {

    return versions.describeWithOrigin();

  }

  /**
   * @return Whether this handler serves the range of its class rather than one of its
   *         own
   */
  boolean inheritsVersions() {

    return versions.inherited();

  }

  /**
   * Where the range came from, ready to be added to a description of the method. It is
   * empty where the method names its range itself.
   *
   * @return The clause to append, or an empty string
   */
  String describeVersionOrigin() {

    return versions.describeOrigin();

  }

  boolean matchesVersion(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.matches(processVersion, resolver);

  }

  /**
   * Whether this handler and the given one serve at least one common process version. Two
   * handlers wired to the same BPMN task are ambiguous exactly then.
   *
   * @param other The other handler
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  boolean overlapsVersions(
      final WorkflowTaskHandler other,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions.overlaps(other.versions, resolver);

  }

  /**
   * @return The version tags this handler's version specifications name
   */
  List<String> versionTags() {

    return versions.versionTags();

  }

  /**
   * @return What this handler serves, for a question the version specifications
   *         answer on their own
   */
  ServedVersions servedVersions() {

    return versions;

  }

  /**
   * Calls the handler method with parameters bound from the aggregate and the invocation
   * context. {@link TaskException} and other {@link RuntimeException}s of the method are
   * passed on unchanged. Checked exceptions are wrapped.
   *
   * @param workflowAggregate The loaded workflow aggregate
   * @param context The invocation context supplied by the adapter
   */
  public void invoke(
      final Object workflowAggregate,
      final TaskInvocationContext context) {

    final var handlerContext = HandlerContexts
        .of(
            workflowAggregate,
            context,
            context::getTaskParameter,
            () -> HandlerContexts.adapt(context.getMultiInstances()));
    final var arguments = new Object[parameterBinders.size()];
    for (int i = 0; i < parameterBinders.size(); ++i) {
      arguments[i] = parameterBinders.get(i).valueFor(handlerContext);
    }
    try {
      method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke @WorkflowTask method '%s'!".formatted(describe()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException; // incl. TaskException
      }
      if (e.getTargetException() instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException(
          "@WorkflowTask method '%s' threw a checked exception!".formatted(describe()), e.getTargetException());
    }

  }

}
