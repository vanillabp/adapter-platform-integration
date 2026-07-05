package io.vanillabp.integration.deployment.validation;

import java.util.List;
import java.util.stream.Collectors;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Validates that classes collected by other build steps (e.g. workflow services or
 * aggregate persistence implementations) are actual CDI beans at runtime.
 */
public class EnsureCollectedClassesAreBeansBuildStepProcessor {

  /**
   * The classes collected are not necessarily injected by application code but looked up
   * dynamically at runtime. This build step prevents ArC from removing them as unused
   * beans (which would also cause false positives in
   * {@link #ensureCollectedClassesAreBeans(ValidationPhaseBuildItem, List, BuildProducer)}
   * since removed beans are not part of the validation context any more).
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
   * Checks each collected class during ArC's validation phase, the documented hook for
   * custom bean validations (in contrast to the bean-registration phase, synthetic beans
   * are visible here as well). A class passes the check if any bean's set of bean types
   * contains the class. ArC's computed bean types are used on purpose: they respect
   * restrictions like {@link jakarta.enterprise.inject.Typed}.
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
              .anyMatch(bean -> bean
                  .getTypes()
                  .stream()
                  .anyMatch(beanType -> beanType.name().equals(requiredType)));
          if (!found) {
            validationErrors.produce(new ValidationErrorBuildItem(
                new IllegalStateException(
                    """
                        Class
                          %s
                        was found by the VanillaBP extension as a
                          %s
                        but neither the class itself nor any implementation is a CDI bean.
                        Please annotate it with a bean-defining annotation such as @ApplicationScoped."""
                        .formatted(buildItem.getClassName(), buildItem.getUsageDescription()))));
          }
        });

  }

}
