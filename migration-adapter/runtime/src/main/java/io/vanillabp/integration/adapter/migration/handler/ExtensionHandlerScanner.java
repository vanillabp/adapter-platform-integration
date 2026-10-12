package io.vanillabp.integration.adapter.migration.handler;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.workflowtask.ServedVersions;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.extension.spi.handler.HandlerValueSource;
import io.vanillabp.spi.service.TaskParam;

/**
 * Scans a <code>&#64;WorkflowService</code> class for the methods of one extension
 * contract, the way {@code WorkflowTaskScanner} does it for <code>&#64;WorkflowTask</code>.
 * It finds the annotated methods and reads the keys they serve. It binds their parameters,
 * first with the extension's own binders and then with the core's. And it checks the return
 * type against what the contract promised.
 * <p>
 * Everything which can be decided at startup is decided here, so a call only reads. A
 * defect ends the boot with a message which names the extension, the method and the fix.
 */
final class ExtensionHandlerScanner {

  private ExtensionHandlerScanner() {
  }

  /**
   * @param contract The contract to scan for
   * @param workflowServiceClass The <code>&#64;WorkflowService</code> class
   * @param workflowAggregateClass Its workflow-aggregate class
   * @param workflowServiceBean Supplies the bean instance of that class
   * @param beanResolver Resolves beans by class, for multi-instance element resolvers
   * @return The methods found, possibly none
   */
  static List<ExtensionHandlerMethod> scan(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver) {

    final var methods = new LinkedList<ExtensionHandlerMethod>();
    for (final var method : workflowServiceClass.getMethods()) {
      final var annotations = method.getAnnotationsByType(contract.getAnnotationType());
      if (annotations.length == 0) {
        continue;
      }
      methods
          .add(
              build(
                  contract,
                  workflowServiceClass,
                  workflowAggregateClass,
                  workflowServiceBean,
                  beanResolver,
                  method,
                  annotations));
    }
    return methods;

  }

  private static ExtensionHandlerMethod build(
      final HandlerContract contract,
      final Class<?> workflowServiceClass,
      final Class<?> workflowAggregateClass,
      final Supplier<Object> workflowServiceBean,
      final Function<Class<?>, Object> beanResolver,
      final Method method,
      final java.lang.annotation.Annotation[] annotations) {

    final var annotationName = "@"
        + contract.getAnnotationType().getSimpleName();
    final var where = "%s method '%s#%s' of extension '%s'"
        .formatted(annotationName, workflowServiceClass.getName(), method.getName(), contract.getExtensionId());

    // plain reflection cannot reach a public method of a package-private bean class, so
    // switch the access check off once, while scanning
    method.trySetAccessible();

    checkAnnotations(contract, method, annotations, where);

    if (!contract.deliversReturnValue() && !method.getReturnType().equals(void.class)) {
      throw new IllegalStateException(
          """
              The %s returns '%s', but this extension does not deliver what its methods return! \
              Declare the method void - nobody would ever read the value."""
              .formatted(where, method.getReturnType().getName()));
    }

    final var lookupKeys = new LinkedHashSet<String>();
    for (final var annotation : annotations) {
      final var keys = contract
          .getLookupKeys()
          .apply(annotation);
      if (keys != null) {
        keys
            .stream()
            .filter(key -> (key != null) && !key.isBlank())
            .forEach(lookupKeys::add);
      }
    }
    if (lookupKeys.isEmpty()) {
      // the convention of every VanillaBP annotation: an attribute which names nothing means
      // that the method's own name is the key
      lookupKeys.add(method.getName());
    }

    final var boundParameters = Arrays
        .stream(method.getParameters())
        .map(parameter -> bind(
            contract,
            workflowAggregateClass,
            beanResolver,
            parameter,
            "parameter '%s' of %s".formatted(parameter.getName(), where)))
        .toList();
    final var binders = boundParameters
        .stream()
        .map(BoundParameter::source)
        .toList();
    // the process variables which the core reads for this method. An extension has to ask
    // its BPMS for them before a delivery arrives. A @TaskParam parameter which an extension
    // binder claimed is the extension's own business and not among them
    final var taskParameterNames = boundParameters
        .stream()
        .map(BoundParameter::taskParameterName)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();

    return new ExtensionHandlerMethod(
        contract, workflowServiceClass, method, workflowServiceBean, binders, List
            .copyOf(lookupKeys), versionsOf(contract, annotations, where), taskParameterNames);

  }

  /**
   * The versions a method serves, read from the attribute which the contract names. A
   * repeatable annotation adds the specifications of every repetition, the same way as it
   * adds its keys. Naming none means every version. A contract which says nothing about
   * versions gets that for all of its methods.
   */
  private static ServedVersions versionsOf(
      final HandlerContract contract,
      final java.lang.annotation.Annotation[] annotations,
      final String where) {

    final var specifications = new LinkedHashSet<String>();
    for (final var annotation : annotations) {
      final var named = contract
          .getVersions()
          .apply(annotation);
      if (named != null) {
        named
            .stream()
            .filter(specification -> (specification != null) && !specification.isBlank())
            .forEach(specifications::add);
      }
    }
    return ServedVersions.parse(List.copyOf(specifications), where);

  }

  /**
   * Lets the extension judge its own annotation while the method with it is at hand. A
   * refusal first names where it happened, then what the extension said. So a developer who
   * reads the boot log finds the method without searching.
   */
  private static void checkAnnotations(
      final HandlerContract contract,
      final Method method,
      final java.lang.annotation.Annotation[] annotations,
      final String where) {

    final var check = contract.getAnnotationCheck();
    if (check == null) {
      return;
    }
    for (final var annotation : annotations) {
      try {
        check.check(annotation, method);
      } catch (final RuntimeException refused) {
        throw new IllegalStateException(
            "The %s was refused by the extension itself: %s".formatted(where, refused.getMessage()), refused);
      }
    }

  }

  /**
   * How one parameter gets its value, and which process variable it reads where the core
   * binds it with <code>&#64;TaskParam</code>.
   *
   * @param source Produces the value at invocation time
   * @param taskParameterName The name the <code>&#64;TaskParam</code> spells, or
   *          <code>null</code> where the core reads no process variable for it
   */
  private record BoundParameter(
                                HandlerValueSource source,
                                String taskParameterName) {
  }

  private static BoundParameter bind(
      final HandlerContract contract,
      final Class<?> workflowAggregateClass,
      final Function<Class<?>, Object> beanResolver,
      final java.lang.reflect.Parameter parameter,
      final String location) {

    final var view = new ReflectiveHandlerParameter(parameter, location);
    for (final var binder : contract.getParameterBinders()) {
      final var bound = binder.bind(view);
      if ((bound != null) && bound.isPresent()) {
        return new BoundParameter(bound.get(), null);
      }
    }

    final var core = CoreParameterBinders
        .bind(parameter, workflowAggregateClass, contract.getCoreParameters(), beanResolver, location);
    if (core != null) {
      return new BoundParameter(core, taskParameterNameOf(contract, parameter));
    }

    throw new IllegalStateException(
        """
            Nothing can bind the %s! Neither a parameter binder of extension '%s' serves it, nor is \
            it one of the parameters VanillaBP binds for this extension (%s). Change its type, \
            annotate it, or let the extension contribute a binder for it."""
            .formatted(
                location,
                contract.getExtensionId(),
                describeCoreParameters(contract, workflowAggregateClass)));

  }

  /**
   * The process variable which the core reads for a parameter it bound. That is the case
   * exactly where the contract allows <code>&#64;TaskParam</code> and the parameter has it,
   * because {@link CoreParameterBinders} tries that kind first.
   */
  private static String taskParameterNameOf(
      final HandlerContract contract,
      final java.lang.reflect.Parameter parameter) {

    if (!contract.getCoreParameters().contains(CoreHandlerParameter.TASK_PARAM)) {
      return null;
    }
    final var taskParam = parameter.getAnnotation(TaskParam.class);
    return taskParam == null
        ? null
        : taskParam.value();

  }

  private static String describeCoreParameters(
      final HandlerContract contract,
      final Class<?> workflowAggregateClass) {

    if (contract.getCoreParameters().isEmpty()) {
      return "this extension allows none";
    }
    return contract
        .getCoreParameters()
        .stream()
        .map(parameter -> switch (parameter) {
          case WORKFLOW_AGGREGATE -> "the workflow aggregate '%s'".formatted(workflowAggregateClass.getName());
          case TASK_PARAM -> "@TaskParam";
          case MULTI_INSTANCE -> "@MultiInstanceElement/@MultiInstanceIndex/@MultiInstanceTotal";
        })
        .collect(java.util.stream.Collectors.joining(", "));

  }

}
