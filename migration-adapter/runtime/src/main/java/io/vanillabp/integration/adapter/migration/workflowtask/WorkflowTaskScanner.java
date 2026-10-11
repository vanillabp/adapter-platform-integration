package io.vanillabp.integration.adapter.migration.workflowtask;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.handler.CoreParameterBinders;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.NoResolver;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Scans a <code>&#64;WorkflowService</code> class for methods with
 * <code>&#64;WorkflowTask</code>, and builds {@link WorkflowTaskHandler}s with their
 * parameter binders (workflow aggregate, <code>&#64;TaskId</code>,
 * <code>&#64;TaskEvent</code>, <code>&#64;TaskParam</code>, multi-instance annotations). It
 * runs once at startup for each workflow service class and BPMN process. For a defect it
 * throws an exception which names the method and the fix.
 * <p>
 * A method which names no <code>version</code> serves the range of the
 * <code>&#64;BpmnProcess</code> for which its handlers are registered (see
 * {@link InheritedVersions}).
 */
class WorkflowTaskScanner {

  /**
   * A workflow task is the handler which binds the most parameters. Everything the core
   * knows how to bind may be in its parameter list, plus the two parameters which only a
   * task has.
   */
  private static final Set<CoreHandlerParameter> CORE_PARAMETERS = Set
      .of(
          CoreHandlerParameter.WORKFLOW_AGGREGATE,
          CoreHandlerParameter.TASK_PARAM,
          CoreHandlerParameter.MULTI_INSTANCE);

  private WorkflowTaskScanner() {
  }

  static List<WorkflowTaskHandler> scan(
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final List<TransactionAnnotationSpec> transactionAnnotations,
      final InheritedVersions inherited,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    final var handlers = new LinkedList<WorkflowTaskHandler>();
    // transaction annotations of the application which cover a handler break the contract
    // of TaskException, and that cannot be worked around at runtime. All offending methods
    // of this class are reported in one exception below
    final var transactionDefects = new LinkedList<String>();
    final var transactionRemedies = new java.util.LinkedHashSet<String>();
    for (final var method : workflowServiceClass.getMethods()) {
      // @WorkflowTask is repeatable, so one method may serve several tasks
      final var annotations = method.getAnnotationsByType(WorkflowTask.class);
      if (annotations.length == 0) {
        continue;
      }
      checkApplicationTransactions(
          workflowServiceClass,
          method,
          transactionAnnotations,
          transactionDefects,
          transactionRemedies,
          findings);
      final var binders = buildParameterBinders(
          workflowServiceClass,
          method,
          workflowAggregateClass,
          beanResolver);
      final var asynchronousTask = Arrays
          .stream(method.getParameters())
          .anyMatch(parameter -> parameter.isAnnotationPresent(TaskId.class));
      // the events to which the method subscribes: the union of the filters of its
      // @TaskEvent parameters. Without a @TaskEvent parameter only CREATED is delivered. A
      // CANCELED call would surprise handlers which do not ask for lifecycle events
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
      // the process variables which the method reads. Only the core knows these names. An
      // adapter whose BPMS ships a payload of variables with every delivery needs them, to
      // keep that payload down to what is read
      final var taskParameters = Arrays
          .stream(method.getParameters())
          .map(parameter -> parameter.getAnnotation(TaskParam.class))
          .filter(java.util.Objects::nonNull)
          .map(TaskParam::value)
          .distinct()
          .sorted()
          .toList();
      // the iterations whose ITEM the method wants. Only the core sees this. An adapter
      // which reads its own BPMN needs it, to say while it deploys that a model iterates
      // without ever naming the value of the round
      final var multiInstanceElements = Arrays
          .stream(method.getParameters())
          .map(parameter -> parameter.getAnnotation(MultiInstanceElement.class))
          .filter(java.util.Objects::nonNull)
          .map(annotation -> elementNamesOf(annotation, beanResolver))
          .toList();
      for (final var annotation : annotations) {
        handlers.add(buildHandler(
            workflowServiceClass,
            method,
            workflowServiceBean,
            binders,
            annotation,
            asynchronousTask,
            subscribedEvents,
            taskParameters,
            multiInstanceElements,
            inherited));
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
      final Set<String> remedies,
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    final var inspected = ApplicationTransactionCheck.inspect(
        workflowServiceClass,
        method,
        transactionAnnotations);
    if (inspected.defect() != null) {
      defects.add(inspected.defect());
      if (inspected.remedy() != null) {
        remedies.add(inspected.remedy());
      }
    }
    if (inspected.notHonored() != null) {
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.CODE,
              "method '%s#%s'".formatted(workflowServiceClass.getName(), method.getName()),
              """
                  This @WorkflowTask method carries a transaction annotation this platform does \
                  NOT honor: %s So the transaction boundary it declares does not exist. On a \
                  workflow task method you want none anyway, since VanillaBP runs the method in a \
                  transaction of its own - but check the rest of your application for the same \
                  annotation, where it silently does nothing as well."""
                  .formatted(inspected.notHonored()));
    }

  }

  /**
   * The key under which the CONFIGURATION knows a <code>&#64;WorkflowTask</code> method. It
   * is the task definition where the annotation names one, the activity id where it names
   * that instead, and the method's name where it names neither.
   * <p>
   * This is not the same as the task definition by which the handler is wired. A method which
   * names only an activity id is wired by that id and has no task definition at all. But the
   * configuration still has to call it something.
   *
   * @param method The annotated method
   * @param annotation Its annotation
   * @return The key under which a task section of the configuration is written
   */
  static String taskConfigurationKeyOf(
      final Method method,
      final WorkflowTask annotation) {

    if (!annotation.taskDefinition().equals(WorkflowTask.USE_METHOD_NAME)) {
      return annotation.taskDefinition();
    }
    return annotation.id().equals(WorkflowTask.USE_METHOD_NAME)
        ? method.getName()
        : annotation.id();

  }

  private static WorkflowTaskHandler buildHandler(
      final Class<?> workflowServiceClass,
      final Method method,
      final Supplier<Object> workflowServiceBean,
      final List<HandlerValueSource> binders,
      final WorkflowTask annotation,
      final boolean asynchronousTask,
      final java.util.Set<TaskEvent.Event> subscribedEvents,
      final List<String> taskParameters,
      final List<Supplier<java.util.Collection<String>>> multiInstanceElements,
      final InheritedVersions inherited) {

    final var location = "%s#%s".formatted(workflowServiceClass.getName(), method.getName());
    // plain reflection cannot reach a public method of a package-private bean class, so
    // switch the access check off once, while scanning
    method.trySetAccessible();
    final var explicitTaskDefinition = !annotation.taskDefinition().equals(WorkflowTask.USE_METHOD_NAME);
    final var explicitActivityId = !annotation.id().equals(WorkflowTask.USE_METHOD_NAME);
    // neither attribute is set: then the method's name matches the task definition OR the
    // activity ID (convention). Explicit attributes wire exactly what they name
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
    final var versions = inherited
        .effectiveFor(ServedVersions.parse(annotation.version(), location));
    return new WorkflowTaskHandler(
        workflowServiceClass, method, workflowServiceBean, binders, taskDefinition, activityId, versions, asynchronousTask, subscribedEvents, taskParameters, multiInstanceElements);

  }

  /**
   * Which elements ONE <code>&#64;MultiInstanceElement</code> parameter asks the item of. A
   * parameter which names its element answers itself. For a parameter with a resolver bean,
   * that bean answers. It is asked when the answer is first needed, because not all beans of
   * the application are built while a workflow service is scanned.
   * <p>
   * A resolver bean which nobody defined answers nothing here. The binder built for the same
   * parameter says what is missing and how to define it. Saying it twice, in two voices,
   * from a question about a model, would only make that message harder to find.
   */
  private static Supplier<java.util.Collection<String>> elementNamesOf(
      final MultiInstanceElement annotation,
      final Function<Class<?>, Object> beanResolver) {

    if (!annotation.value().equals(MultiInstanceElement.USE_RESOLVER)) {
      final var named = List.of(annotation.value());
      return () -> named;
    }
    final var resolverClass = annotation.resolverBean();
    if (resolverClass.equals(NoResolver.class)) {
      // neither the name nor a resolver. The binder of this same parameter refuses it
      return List::of;
    }
    return () -> {
      final var resolver = (MultiInstanceElementResolver<?, ?>) beanResolver.apply(resolverClass);
      if (resolver == null) {
        return List.of();
      }
      final var names = resolver.getNames();
      return names == null
          ? List.of()
          : names;
    };

  }

  private static List<HandlerValueSource> buildParameterBinders(
      final Class<?> workflowServiceClass,
      final Method method,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var binders = new LinkedList<HandlerValueSource>();
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

  private static HandlerValueSource buildParameterBinder(
      final Class<?> workflowServiceClass,
      final Method method,
      final Parameter parameter,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver) {

    final var location = "parameter '%s' of @WorkflowTask method '%s#%s'"
        .formatted(parameter.getName(), workflowServiceClass.getName(), method.getName());

    // the two parameters which only a task has. The rest is what every kind of handler
    // method may take, and it comes from the binders which all three scanners share
    if (parameter.isAnnotationPresent(TaskId.class)) {
      if (!parameter.getType().equals(String.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskId but is not of type String! The task's ID is \
                passed as a String - change the parameter's type."""
                .formatted(location));
      }
      return context -> context
          .payload(TaskInvocationContext.class)
          .getTaskId();
    }

    if (parameter.isAnnotationPresent(TaskEvent.class)) {
      if (!parameter.getType().equals(TaskEvent.Event.class)) {
        throw new IllegalStateException(
            """
                The %s is annotated with @TaskEvent but is not of type TaskEvent.Event! Change \
                the parameter's type."""
                .formatted(location));
      }
      return context -> context
          .payload(TaskInvocationContext.class)
          .getTaskEvent();
    }

    final var binder = CoreParameterBinders.bind(
        parameter,
        workflowAggregateClass,
        CORE_PARAMETERS,
        beanResolver,
        location);
    if (binder != null) {
      return binder;
    }
    throw new IllegalStateException(
        """
            The %s is neither annotated (@TaskId, @TaskEvent, @TaskParam, @MultiInstanceIndex, \
            @MultiInstanceTotal, @MultiInstanceElement) nor of the workflow-aggregate type '%s'! \
            Annotate the parameter or change its type to the workflow aggregate."""
            .formatted(location, workflowAggregateClass.getName()));

  }

}
