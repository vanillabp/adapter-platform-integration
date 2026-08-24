package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.spi.service.TaskException;

/**
 * One <code>&#64;WorkflowTask</code> annotated method wired to a BPMN task: the
 * target bean, the method and the parameter binders resolved at registration time.
 * Built by {@link WorkflowTaskScanner}, registered per (workflow module, BPMN
 * process ID) in the {@link WorkflowTaskRegistry} and invoked by
 * {@link io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService#executeWorkflowTask}.
 */
public class WorkflowTaskHandler {

  /**
   * Binds one parameter of the handler method per invocation.
   */
  interface ParameterBinder {

    Object bind(
        Object workflowAggregate,
        TaskInvocationContext context);

  }

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<ParameterBinder> parameterBinders;

  /**
   * The task definition this handler is wired to, or <code>null</code> if wired by
   * activity ID only.
   */
  private final String taskDefinition;

  /**
   * The BPMN activity ID this handler is wired to, or <code>null</code> if wired by
   * task definition only.
   */
  private final String activityId;

  private final List<VersionRange> versions;

  /**
   * Whether the method declares a <code>&#64;TaskId</code> parameter: the task is
   * completed asynchronously later and MUST NOT be completed when the method
   * returns.
   */
  private final boolean asynchronousTask;

  /**
   * The lifecycle events the method subscribes to (union of its
   * <code>&#64;TaskEvent</code> parameters' filters; only CREATED without such a
   * parameter). {@link io.vanillabp.spi.service.TaskEvent.Event#ALL} subscribes to
   * everything.
   */
  private final java.util.Set<io.vanillabp.spi.service.TaskEvent.Event> subscribedEvents;

  /**
   * The process variables the method reads with <code>&#64;TaskParam</code>, sorted and
   * duplicate-free. The core reports them to the adapters through
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker#taskParameterNames},
   * so a BPMS delivering a variable payload knows what to put into it.
   */
  private final List<String> taskParameters;

  /**
   * Whether some BPMN task matched this handler during wiring validation - input
   * for the per-module unwired-methods check (a handler registered under several
   * BPMN processes via {@code secondaryBpmnProcesses} legitimately matches in only
   * one of them).
   */
  private volatile boolean wired = false;

  WorkflowTaskHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<ParameterBinder> parameterBinders,
      final String taskDefinition,
      final String activityId,
      final List<VersionRange> versions,
      final boolean asynchronousTask,
      final java.util.Set<io.vanillabp.spi.service.TaskEvent.Event> subscribedEvents,
      final List<String> taskParameters) {

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

  }

  /**
   * @return The process variables the method reads with <code>&#64;TaskParam</code>
   */
  List<String> getTaskParameters() {

    return taskParameters;

  }

  /**
   * Whether the method subscribes to the given lifecycle event - non-matching
   * deliveries (e.g. CANCELED to a method without a <code>&#64;TaskEvent</code>
   * parameter) are skipped by the core without invoking the method.
   *
   * @param event The delivered event
   * @return Whether to invoke the method
   */
  public boolean acceptsEvent(
      final io.vanillabp.spi.service.TaskEvent.Event event) {

    return subscribedEvents.contains(io.vanillabp.spi.service.TaskEvent.Event.ALL) || subscribedEvents.contains(event);

  }

  public String getTaskDefinition() {

    return taskDefinition;

  }

  public String getActivityId() {

    return activityId;

  }

  public boolean isAsynchronousTask() {

    return asynchronousTask;

  }

  public void markWired() {

    wired = true;

  }

  public boolean isWired() {

    return wired;

  }

  public String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * The key(s) this handler is wired by, for guiding messages.
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
   * The version specification(s) this method names, for messages about a method
   * serving no version the BPMS holds.
   *
   * @return The specifications, comma separated
   */
  String describeVersions() {

    return versions
        .stream()
        .map(VersionRange::toString)
        .map("'%s'"::formatted)
        .collect(java.util.stream.Collectors.joining(", "));

  }

  boolean matchesVersion(
      final String processVersion) {

    return matchesVersion(processVersion, VersionRange.NO_RESOLVER);

  }

  boolean matchesVersion(
      final String processVersion,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions
        .stream()
        .anyMatch(version -> version.matches(processVersion, resolver));

  }

  /**
   * Whether this handler and the given one serve at least one common process version -
   * two handlers wired to the same BPMN task are ambiguous exactly then.
   *
   * @param other The other handler
   * @param resolver Resolves version tags of the BPMN process both belong to
   * @return Whether both serve a common version
   */
  boolean overlapsVersions(
      final WorkflowTaskHandler other,
      final VersionRange.ProcessVersionResolver resolver) {

    return versions
        .stream()
        .anyMatch(version -> other.versions
            .stream()
            .anyMatch(otherVersion -> version.overlaps(otherVersion, resolver)));

  }

  /**
   * @return The version tags this handler's version specifications name
   */
  List<String> versionTags() {

    return versions
        .stream()
        .flatMap(version -> version.versionTags().stream())
        .distinct()
        .toList();

  }

  /**
   * Invokes the handler method with parameters bound from the aggregate and the
   * invocation context. {@link TaskException} and other
   * {@link RuntimeException}s of the method propagate unchanged; checked
   * exceptions are wrapped.
   *
   * @param workflowAggregate The loaded workflow aggregate
   * @param context The invocation context supplied by the adapter
   */
  public void invoke(
      final Object workflowAggregate,
      final TaskInvocationContext context) {

    final var arguments = new Object[parameterBinders.size()];
    for (int i = 0; i < parameterBinders.size(); ++i) {
      arguments[i] = parameterBinders.get(i).bind(workflowAggregate, context);
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
