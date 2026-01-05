package io.vanillabp.integration.deployment.validation;

import java.util.List;

import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

public class EnsureCollectedClassesAreBeansBuildStepProcessor {

  @BuildStep
  void ensureCollectedClassesAreBeans(
      final BeanRegistrationPhaseBuildItem beanRegistrationPhase,
      final List<EnsureClassIsBeanValidationBuildItem> classIsBeanValidationBuildItems,
      final BuildProducer<ValidationErrorBuildItem> validationErrors) {

    final var index = beanRegistrationPhase
        .getBeanProcessor()
        .getBeanDeployment()
        .getBeanArchiveIndex();
    final var beans = beanRegistrationPhase
        .getContext()
        .beans()
        .stream()
        .toList();

    classIsBeanValidationBuildItems
        .forEach(buildItem -> {
          final var requiredType = buildItem.getClassName();
          final var found = beans.stream()
              .anyMatch(bean -> isBeanAssignableTo(bean, requiredType, index));
          if (!found) {
            validationErrors.produce(new ValidationPhaseBuildItem.ValidationErrorBuildItem(
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

  private boolean isBeanAssignableTo(
      final BeanInfo bean,
      final DotName requiredType,
      final IndexView index) {

    final var beanClass = bean.getBeanClass();
    if (beanClass.equals(requiredType)) {
      return true;
    }

    final var classInfo = index.getClassByName(beanClass);
    if (classInfo == null) {
      return false;
    }

    // Interfaces prüfen
    for (final var iface : classInfo.interfaceNames()) {
      if (iface.equals(requiredType)) {
        return true;
      }
      if (isAssignableViaHierarchy(iface, requiredType, index)) {
        return true;
      }
    }

    // Superklasse prüfen
    final var superClass = classInfo.superName();
    return superClass != null && isAssignableViaHierarchy(superClass, requiredType, index);
  }

  private boolean isAssignableViaHierarchy(
      final DotName current,
      final DotName target,
      final IndexView index) {

    if (current.equals(target)) {
      return true;
    }

    final var info = index.getClassByName(current);
    if (info == null) {
      return false;
    }

    for (final var iface : info.interfaceNames()) {
      if (isAssignableViaHierarchy(iface, target, index)) {
        return true;
      }
    }

    final var superClass = info.superName();
    return superClass != null && isAssignableViaHierarchy(superClass, target, index);
  }

}
