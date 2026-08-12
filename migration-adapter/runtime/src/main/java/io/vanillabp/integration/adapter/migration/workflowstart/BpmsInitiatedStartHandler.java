package io.vanillabp.integration.adapter.migration.workflowstart;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.workflowtask.VersionRange;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;

/**
 * One <code>&#64;WorkflowStartedByBpms</code> method of a workflow service class,
 * with its parameter binders and the start event it serves.
 */
public class BpmsInitiatedStartHandler {

  /**
   * Binds one parameter of the method from the aggregate VanillaBP built and the
   * adapter's notification.
   */
  @FunctionalInterface
  interface ParameterBinder {

    Object bind(
        Object workflowAggregate,
        BpmsInitiatedStartContext context);

  }

  private final Class<?> workflowServiceClass;

  private final Method method;

  private final Supplier<Object> workflowServiceBean;

  private final List<ParameterBinder> binders;

  /**
   * The BPMN id of the start event this method serves, or <code>null</code> for
   * every BPMS-initiated start event of the process.
   */
  private final String startEventId;

  private final List<VersionRange> versions;

  /**
   * Whether the method RETURNS the aggregate (instead of modifying the one passed
   * in). Both shapes are allowed; a returned aggregate replaces what VanillaBP
   * built.
   */
  private final boolean returnsAggregate;

  BpmsInitiatedStartHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<ParameterBinder> binders,
      final String startEventId,
      final List<VersionRange> versions,
      final boolean returnsAggregate) {

    this.workflowServiceClass = workflowServiceClass;
    this.method = method;
    this.workflowServiceBean = workflowServiceBean;
    this.binders = binders;
    this.startEventId = startEventId;
    this.versions = versions;
    this.returnsAggregate = returnsAggregate;

  }

  public String getStartEventId() {

    return startEventId;

  }

  public boolean isReturningAggregate() {

    return returnsAggregate;

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

    return startEventId == null
        ? "every BPMS-initiated start event"
        : "start event '%s'".formatted(startEventId);

  }

  boolean matchesStartEvent(
      final String eventId) {

    return (startEventId == null) || startEventId.equals(eventId);

  }

  boolean matchesVersion(
      final String processVersion) {

    return versions
        .stream()
        .anyMatch(version -> version.matches(processVersion));

  }

  /**
   * Invokes the method with bound parameters. Runtime exceptions of the method
   * propagate unchanged - the transaction of the start rolls back, so no aggregate
   * exists and the BPMS retries.
   *
   * @param workflowAggregate The aggregate VanillaBP built
   * @param context The adapter's notification
   * @return The aggregate the method returned, or <code>null</code> for a
   *         <code>void</code> method
   */
  Object invoke(
      final Object workflowAggregate,
      final BpmsInitiatedStartContext context) {

    final var arguments = binders
        .stream()
        .map(binder -> binder.bind(workflowAggregate, context))
        .toArray();
    try {
      return method.invoke(workflowServiceBean.get(), arguments);
    } catch (final IllegalAccessException e) {
      throw new IllegalStateException(
          "Could not invoke @WorkflowStartedByBpms method '%s'!".formatted(describe()), e);
    } catch (final InvocationTargetException e) {
      if (e.getTargetException() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException(
          "The @WorkflowStartedByBpms method '%s' threw a checked exception!".formatted(describe()), e
              .getTargetException());
    }

  }

}
