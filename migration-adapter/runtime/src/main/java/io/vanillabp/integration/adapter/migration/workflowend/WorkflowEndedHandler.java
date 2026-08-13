package io.vanillabp.integration.adapter.migration.workflowend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;

/**
 * One <code>&#64;WorkflowEnded</code> method of a workflow service class, with its
 * parameter binders and the end event it serves.
 */
public class WorkflowEndedHandler {

  /**
   * Binds one parameter of the method from the workflow aggregate and the adapter's
   * notification.
   */
  @FunctionalInterface
  interface ParameterBinder {

    Object bind(
        Object workflowAggregate,
        WorkflowEndedContext context);

  }

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<ParameterBinder> binders;

  /**
   * The BPMN id of the end event this method serves, or <code>null</code> for every
   * end of the workflow.
   */
  private final String endEventId;

  private final List<VersionRange> versions;

  WorkflowEndedHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<ParameterBinder> binders,
      final String endEventId,
      final List<VersionRange> versions) {

    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.binders = binders;
    this.endEventId = endEventId;
    this.versions = versions;

  }

  public String getEndEventId() {

    return endEventId;

  }

  /**
   * @return The method, for guiding messages
   */
  public String describe() {

    return "%s#%s".formatted(workflowServiceClass.getName(), method.getName());

  }

  /**
   * @return What this handler is wired to, for guiding messages
   */
  public String describeWiring() {

    return endEventId == null
        ? "every end of the workflow"
        : "end event '%s'".formatted(endEventId);

  }

  boolean matchesEndEvent(
      final String eventId) {

    // a BPMS reporting no end event id serves the methods which asked for none
    return (endEventId == null) || endEventId.equals(eventId);

  }

  boolean matchesVersion(
      final String processVersion) {

    return versions
        .stream()
        .anyMatch(version -> version.matches(processVersion));

  }

  /**
   * Invokes the method with bound parameters. Runtime exceptions propagate - the
   * transaction rolls back and the BPMS applies its retry semantics.
   *
   * @param workflowAggregate The workflow aggregate of the ended workflow
   * @param context The adapter's notification
   */
  void invoke(
      final Object workflowAggregate,
      final WorkflowEndedContext context) {

    final var arguments = binders
        .stream()
        .map(binder -> binder.bind(workflowAggregate, context))
        .toArray();
    try {
      method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke @WorkflowEnded method '%s'!".formatted(describe()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(
          "The @WorkflowEnded method '%s' threw a checked exception!".formatted(describe()), e.getTargetException());
    }

  }

}
