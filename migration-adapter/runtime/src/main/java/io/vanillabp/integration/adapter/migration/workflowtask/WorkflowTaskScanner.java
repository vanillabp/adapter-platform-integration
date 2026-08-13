package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.values.ValueConversion;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler.ParameterBinder;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.NoResolver;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Scans a <code>&#64;WorkflowService</code> class for
 * <code>&#64;WorkflowTask</code> annotated methods and builds
 * {@link WorkflowTaskHandler}s including the parameter binders (workflow aggregate,
 * <code>&#64;TaskId</code>, <code>&#64;TaskEvent</code>, <code>&#64;TaskParam</code>,
 * multi-instance annotations). Runs once at startup per workflow service class -
 * defects yield guiding exceptions naming the method and the fix.
 */
class WorkflowTaskScanner {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowTaskScanner.class);

  private WorkflowTaskScanner() {
  }

  static List<WorkflowTaskHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final List<TransactionAnnotationSpec> transactionAnnotations) {

    final var handlers = new LinkedList<WorkflowTaskHandler>();
    // transaction annotations of the application covering a handler break the
    // TaskException contract and cannot be worked around at runtime - all offending
    // methods of this class are reported in one exception below
    final var transactionDefects = new LinkedList<String>();
    final var transactionRemedies = new java.util.LinkedHashSet<String>();
    for (final var method : workflowServiceClass.getMethods()) {
      // @WorkflowTask is repeatable: one method may serve several tasks
      final var annotations = method.getAnnotationsByType(WorkflowTask.class);
      if (annotations.length == 0) {
        continue;
      }
      checkApplicationTransactions(
          workflowServiceClass,
          method,
          transactionAnnotations,
          transactionDefects,
          transactionRemedies);
      final var binders = buildParameterBinders(
          workflowServiceClass,
          method,
          workflowAggregateClass,
          beanResolver);
      final var asynchronousTask = Arrays
          .stream(method.getParameters())
          .anyMatch(parameter -> parameter.isAnnotationPresent(TaskId.class));
      // the events the method subscribes to: the union of its @TaskEvent
      // parameters' filters; without a @TaskEvent parameter only CREATED is
      // delivered (a CANCELED invocation would surprise handlers not asking
      // for lifecycle events)
      final var subscribedEvents = java.util.EnumSet.noneOf(TaskEvent.Event.class);
      Arrays
          .stream(method.getParameters())
          .map(parameter -> parameter.getAnnotation(TaskEvent.class))
          .filter(java.util.Objects::nonNull)
          .flatMap(taskEvent -> Arrays.stream(taskEvent.value()))
          .forEach(subscribedEvents::add);
      if (subscribedEvents.isEmpty()) {
        subscribedEvents.add(TaskEvent.Event.CREATED);
      }
      for (final var annotation : annotations) {
        handlers.add(buildHandler(
            workflowServiceClass,
            method,
            workflowServiceBean,
            binders,
            annotation,
            asynchronousTask,
            subscribedEvents));
      }
    }
    if (!transactionDefects.isEmpty()) {
      throw new IllegalStateException(
          ApplicationTransactionCheck.buildFailureMessage(
              transactionDefects,
              transactionRemedies,
              workflowServiceClass));
    }
    return handlers;

  }

  private static void checkApplicationTransactions(
      final Class<?> workflowServiceClass,
      final Method method,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final List<String> defects,
      final Set<String> remedies) {

    final var findings = ApplicationTransactionCheck.inspect(
        workflowServiceClass,
        method,
        transactionAnnotations);
    if (findings.defect() != null) {
      defects.add(findings.defect());
      if (findings.remedy() != null) {
        remedies.add(findings.remedy());
      }
    }
    if (findings.notHonored() != null) {
      log.warn(
          """
              The @WorkflowTask method '{}#{}' carries a transaction annotation this platform does \
              NOT honor: {} So the transaction boundary it declares does not exist. On a workflow \
              task method you want none anyway, since VanillaBP runs the method in a transaction of \
              its own - but check the rest of your application for the same annotation, where it \
              silently does nothing as well.""",
          workflowServiceClass.getName(),
          method.getName(),
          findings.notHonored());
    }

  }

  private static WorkflowTaskHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<ParameterBinder> binders,
      final WorkflowTask annotation,
      final boolean asynchronousTask,
      final java.util.Set<TaskEvent.Event> subscribedEvents) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // a public method of a package-private bean class is not accessible through
    // plain reflection - lift the check once at scan time
    method.trySetAccessible();
    final var explicitTaskDefinition = !annotation.taskDefinition().equals(WorkflowTask.USE_METHOD_NAME);
    final var explicitActivityId = !annotation.id().equals(WorkflowTask.USE_METHOD_NAME);
    // neither attribute set: the method's name matches the task definition OR the
    // activity ID (convention); explicit attributes wire exactly what is given
    final String taskDefinition;
    final String activityId;
    if (!explicitTaskDefinition && !explicitActivityId) {
      taskDefinition = method.getName();
      activityId = method.getName();
    } else {
      taskDefinition = explicitTaskDefinition
          ? annotation.taskDefinition()
          : null;
      activityId = explicitActivityId
          ? annotation.id()
          : null;
    }
    final var versions = Arrays
        .stream(annotation.version())
        .map(version -> VersionRange.parse(version, location))
        .toList();
    return new WorkflowTaskHandler(
        workflowServiceClass, method, workflowServiceBean, binders, taskDefinition, activityId, versions, asynchronousTask, subscribedEvents);

  }

  private static List<ParameterBinder> buildParameterBinders(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var binders = new LinkedList<ParameterBinder>();
    for (final var parameter : method.getParameters()) {
      binders.add(buildParameterBinder(
          workflowServiceClass,
          method,
          parameter,
          workflowAggregateClass,
          beanResolver));
    }
    return binders;

  }

  private static ParameterBinder buildParameterBinder(
      final Class<?> workflowServiceClass,
      final Method method,
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var location = "parameter '%s' of @WorkflowTask method '%s#%s'"
        .formatted(parameter.getName(), workflowServiceClass.getName(), method.getName());

    if (parameter.isAnnotationPresent(TaskId.class)) {
      if (!parameter.getType().equals(String.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskId but is not of type String! The task's ID is \
                passed as a String - change the parameter's type."""
                .formatted(location));
      }
      return (
          aggregate,
          context) -> context.getTaskId();
    }

    if (parameter.isAnnotationPresent(TaskEvent.class)) {
      if (!parameter.getType().equals(TaskEvent.Event.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskEvent but is not of type TaskEvent.Event! Change \
                the parameter's type."""
                .formatted(location));
      }
      return (
          aggregate,
          context) -> context.getTaskEvent();
    }

    final var taskParam = parameter.getAnnotation(TaskParam.class);
    if (taskParam != null) {
      final var targetType = parameter.getType();
      return (
          aggregate,
          context) -> ValueConversion.convert(
              context.getTaskParameter(taskParam.value()),
              targetType,
              "@TaskParam(\"%s\") %s".formatted(taskParam.value(), location));
    }

    final var multiInstanceIndex = parameter.getAnnotation(MultiInstanceIndex.class);
    if (multiInstanceIndex != null) {
      requireIntParameter(parameter, location, "@MultiInstanceIndex");
      return (
          aggregate,
          context) -> requireMultiInstance(
              context,
              multiInstanceIndex.value(),
              location).index();
    }

    final var multiInstanceTotal = parameter.getAnnotation(MultiInstanceTotal.class);
    if (multiInstanceTotal != null) {
      requireIntParameter(parameter, location, "@MultiInstanceTotal");
      return (
          aggregate,
          context) -> requireMultiInstance(
              context,
              multiInstanceTotal.value(),
              location).total();
    }

    final var multiInstanceElement = parameter.getAnnotation(MultiInstanceElement.class);
    if (multiInstanceElement != null) {
      return buildMultiInstanceElementBinder(multiInstanceElement, location, beanResolver);
    }

    // unannotated: the workflow aggregate
    if (parameter.getType().isAssignableFrom(workflowAggregateClass)) {
      return (
          aggregate,
          context) -> aggregate;
    }
    throw new IllegalStateException(
        """
            The %s is neither annotated (@TaskId, @TaskEvent, @TaskParam, @MultiInstanceIndex, \
            @MultiInstanceTotal, @MultiInstanceElement) nor of the workflow-aggregate type '%s'! \
            Annotate the parameter or change its type to the workflow aggregate."""
            .formatted(location, workflowAggregateClass.getName()));

  }

  private static ParameterBinder buildMultiInstanceElementBinder(
      final MultiInstanceElement annotation,
      final String location,
      final Function<Class<?>, Object> beanResolver) {

    final var resolverClass = annotation.resolverBean();
    final var hasResolver = !resolverClass.equals(NoResolver.class);
    final var hasName = !annotation.value().equals(MultiInstanceElement.USE_RESOLVER);
    if (hasResolver == hasName) {
      throw new IllegalStateException(
          """
              The %s has to set EITHER @MultiInstanceElement's value (the name of the \
              multi-instance element) OR its resolverBean!"""
              .formatted(location));
    }
    if (hasName) {
      return (
          aggregate,
          context) -> requireMultiInstance(
              context,
              annotation.value(),
              location).element();
    }
    return (
        aggregate,
        context) -> {
      @SuppressWarnings("unchecked")
      final var resolver = (MultiInstanceElementResolver<Object, Object>) beanResolver.apply(resolverClass);
      if (resolver == null) {
        throw new IllegalStateException(
            """
                No bean of the resolver class '%s' (used by the %s) is available! Define it as a \
                bean of your application."""
                .formatted(resolverClass.getName(), location));
      }
      return resolver.resolve(aggregate, adaptMultiInstances(context.getMultiInstances()));
    };

  }

  /**
   * Adapts the neutral adapter-supplied multi-instance values to the SPI's
   * {@link MultiInstanceElementResolver.MultiInstance} view, preserving the
   * outermost-first order.
   */
  private static Map<String, MultiInstanceElementResolver.MultiInstance<Object>> adaptMultiInstances(
      final Map<String, MultiInstanceValue> multiInstances) {

    final var adapted = new LinkedHashMap<String, MultiInstanceElementResolver.MultiInstance<Object>>();
    multiInstances.forEach((
        name,
        value) -> adapted.put(name, new MultiInstanceElementResolver.MultiInstance<>() {

          @Override
          public Object getElement() {
            return value.element();
          }

          @Override
          public int getIndex() {
            return value.index();
          }

          @Override
          public int getTotal() {
            return value.total();
          }

        }));
    return adapted;

  }

  private static MultiInstanceValue requireMultiInstance(
      final TaskInvocationContext context,
      final String name,
      final String location) {

    final var multiInstance = context.getMultiInstances().get(name);
    if (multiInstance == null) {
      throw new IllegalStateException(
          """
              No multi-instance context named '%s' was supplied by the BPMS adapter for the %s! \
              Supplied multi-instance contexts: %s. Check the name against the BPMN's \
              multi-instance element."""
              .formatted(name, location, context.getMultiInstances().keySet()));
    }
    return multiInstance;

  }

  private static void requireIntParameter(
      final Parameter parameter,
      final String location,
      final String annotationName) {

    if (!parameter.getType().equals(int.class) && !parameter.getType().equals(Integer.class)) {
      throw new IllegalStateException(
          """
              The %s is annotated with %s but is not of type int/Integer! Change the parameter's \
              type."""
              .formatted(location, annotationName));
    }

  }

}
