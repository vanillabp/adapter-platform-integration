package io.vanillabp.integration.adapter.migration.workflowend;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.CoreParameterBinders;
import io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions;
import io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;

/**
 * Scans a <code>&#64;WorkflowService</code> class for <code>&#64;WorkflowEnded</code>
 * methods and builds their handlers. It runs once at startup for each workflow service
 * class. For a defect it throws an exception which names the method and the fix.
 * <p>
 * Of all handler kinds, this one binds the fewest parameters. A workflow which ended has
 * no task and no variables worth binding. So a method may ask for the workflow aggregate
 * and a {@link WorkflowEnd}, nothing more.
 */
public final class WorkflowEndedScanner {

  /**
   * A workflow which ended has no task and no variables worth binding. The aggregate is all
   * the core has left to offer.
   */
  private static final java.util.Set<CoreHandlerParameter> CORE_PARAMETERS = java.util.Set
      .of(CoreHandlerParameter.WORKFLOW_AGGREGATE);

  /**
   * No parameter of such a method resolves a bean. Only <code>&#64;MultiInstanceElement</code>
   * would, and this handler does not allow it.
   */
  private static final java.util.function.Function<Class<?>, Object> NO_BEAN_RESOLVER = beanClass -> null;

  private WorkflowEndedScanner() {
  }

  /**
   * Reads the <code>&#64;WorkflowEnded</code> methods of one workflow service class and
   * builds one handler per method, with the parameter binders the method needs. It looks at
   * every public method of the class, the inherited ones included.
   *
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass The workflow-aggregate class of that service
   * @param workflowServiceBean Supplies the bean instance of the class
   * @param inherited What a method naming no <code>version</code> serves: the
   *          range of the <code>&#64;BpmnProcess</code> these handlers are registered
   *          for
   * @return The handlers, possibly empty
   */
  public static List<WorkflowEndedHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final InheritedVersions inherited) {

    final var handlers = new LinkedList<WorkflowEndedHandler>();
    for (final var method : workflowServiceClass.getMethods()) {
      final var annotation = method.getAnnotation(WorkflowEnded.class);
      if (annotation == null) {
        continue;
      }
      handlers
          .add(
              buildHandler(
                  workflowServiceClass,
                  method,
                  workflowAggregateClass,
                  workflowServiceBean,
                  annotation,
                  inherited));
    }
    return handlers;

  }

  private static WorkflowEndedHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final WorkflowEnded annotation,
      final InheritedVersions inherited) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // plain reflection cannot reach a public method of a package-private bean class, so
    // switch the access check off once, while scanning
    method.trySetAccessible();

    if (!method.getReturnType().equals(void.class)) {
      throw new IllegalStateException(
          """
              The @WorkflowEnded method '%s' returns '%s'! A workflow which ended cannot be \
              influenced any more, so the method has nothing to return - declare it void."""
              .formatted(location, method.getReturnType().getName()));
    }

    final var binders = Arrays
        .stream(method.getParameters())
        .map(parameter -> buildParameterBinder(
            parameter,
            workflowAggregateClass,
            "parameter '%s' of @WorkflowEnded method '%s'".formatted(parameter.getName(), location)))
        .toList();

    final var versions = inherited
        .effectiveFor(ServedVersions.parse(annotation.version(), location));
    final var endEventId = annotation.id().equals(WorkflowEnded.ANY_END_EVENT)
        ? null
        : annotation.id();

    return new WorkflowEndedHandler(
        workflowServiceClass, method, workflowServiceBean, binders, endEventId, versions);

  }

  private static HandlerValueSource buildParameterBinder(
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final String location) {

    // the one value which only this kind of handler has. The binders which all handler
    // kinds share do the rest
    if (parameter.getType().equals(WorkflowEnd.class)) {
      return context -> {
        final var end = context.payload(WorkflowEndedContext.class);
        return new WorkflowEnd(end.getKind(), end.getEndTime(), end.getEndEventId());
      };
    }

    final var binder = CoreParameterBinders
        .bind(parameter, workflowAggregateClass, CORE_PARAMETERS, NO_BEAN_RESOLVER, location);
    if (binder != null) {
      return binder;
    }

    throw new IllegalStateException(
        """
            The %s is neither of the workflow-aggregate type '%s' nor of type '%s'! A workflow which \
            ended offers nothing else: its tasks are gone and its variables are the aggregate you \
            already hold."""
            .formatted(location, workflowAggregateClass.getName(), WorkflowEnd.class.getName()));

  }

}
