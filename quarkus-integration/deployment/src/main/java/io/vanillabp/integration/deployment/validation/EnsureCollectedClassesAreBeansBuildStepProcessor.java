package io.vanillabp.integration.deployment.validation;

import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Checks that the classes which other build steps collected are real CDI beans at runtime.
 * Examples are workflow services and aggregate persistence implementations.
 */
public class EnsureCollectedClassesAreBeansBuildStepProcessor {

  /**
   * Quarkus builds this processor while it augments the application and calls the build
   * steps below on it. Nothing else builds it, and no step keeps state in it.
   */
  public EnsureCollectedClassesAreBeansBuildStepProcessor() {
  }

  /**
   * Application code does not always inject the collected classes. They are often looked up
   * at runtime. This build step stops ArC from removing them as unused beans. A removed bean
   * is no longer part of the validation context. So
   * {@link #ensureCollectedClassesAreBeans(ValidationPhaseBuildItem, List, BuildProducer)}
   * would report it by mistake.
   *
   * @param classIsBeanValidationBuildItems The classes collected by other build steps
   * @param unremovableBeans Producer for unremovable-bean build items
   */
  @BuildStep
  void preserveCollectedBeans(
      final List<EnsureClassIsBeanValidationBuildItem> classIsBeanValidationBuildItems,
      final BuildProducer<UnremovableBeanBuildItem> unremovableBeans) {

    if (classIsBeanValidationBuildItems.isEmpty()) {
      return;
    }
    unremovableBeans.produce(UnremovableBeanBuildItem
        .beanTypes(classIsBeanValidationBuildItems
            .stream()
            .map(EnsureClassIsBeanValidationBuildItem::getClassName)
            .collect(Collectors.toSet())));

  }

  /**
   * Checks each collected class during ArC's validation phase. This phase is the documented
   * hook for custom bean validations. Unlike the bean-registration phase, it also sees
   * synthetic beans. A class passes the check if the bean types of any bean contain the
   * class. We use the bean types which ArC computed on purpose, because they respect
   * restrictions like {@link jakarta.enterprise.inject.Typed}.
   * <p>
   * A class which VanillaBP registers by its own name needs more. There the bean has to BE
   * that class. A bean of a subclass is a different workflow service, and it serves a BPMN
   * process of its own.
   *
   * @param validationPhase The ArC validation phase
   * @param classIsBeanValidationBuildItems The classes collected by other build steps
   * @param validationErrors Producer for validation errors failing the build
   */
  @BuildStep
  void ensureCollectedClassesAreBeans(
      final ValidationPhaseBuildItem validationPhase,
      final List<EnsureClassIsBeanValidationBuildItem> classIsBeanValidationBuildItems,
      final BuildProducer<ValidationErrorBuildItem> validationErrors) {

    final var beans = validationPhase
        .getContext()
        .beans()
        .stream()
        .toList();

    classIsBeanValidationBuildItems
        .forEach(buildItem -> {
          final var requiredType = buildItem.getClassName();
          final var found = beans
              .stream()
              .anyMatch(bean -> buildItem.isABeanOfASubclassCounts()
                  ? bean
                      .getTypes()
                      .stream()
                      .anyMatch(beanType -> beanType.name().equals(requiredType))
                  : isBeanOfExactly(bean, requiredType));
          if (!found) {
            validationErrors.produce(new ValidationErrorBuildItem(
                new IllegalStateException(
                    """
                        Class
                          %s
                        was found by the VanillaBP extension as a
                          %s
                        but %s.
                        %s"""
                        .formatted(
                            buildItem.getClassName(),
                            buildItem.getUsageDescription(),
                            buildItem.isABeanOfASubclassCounts()
                                ? "neither the class itself nor any implementation is a CDI bean"
                                : "the class itself is not a CDI bean",
                            buildItem.getRemedy() == null
                                ? "Please annotate it with a bean-defining annotation such as @ApplicationScoped."
                                : buildItem.getRemedy()))));
          }
        });

  }

  /**
   * Whether a bean stands for exactly the given class. This is the class ArC builds the bean
   * from. For a class bean it is the class itself, for a producer it is the returned type.
   */
  private static boolean isBeanOfExactly(
      final BeanInfo bean,
      final org.jboss.jandex.DotName requiredType) {

    final var implementation = bean.getImplClazz();
    return (implementation != null) && implementation.name().equals(requiredType);

  }

}
