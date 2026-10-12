package io.vanillabp.integration.deployment.workflowtask;

import java.util.LinkedHashSet;

import org.jboss.jandex.DotName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.vanillabp.integration.deployment.validation.EnsureClassIsBeanValidationBuildItem;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.NoResolver;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the resolver beans of
 * <code>&#64;MultiInstanceElement(resolverBean = ...)</code> alive.
 * <p>
 * Nothing injects such a resolver bean. An annotation names it, and VanillaBP asks the
 * container for it while the task runs. Quarkus removes beans nobody injects while it
 * builds the application. So the lookup found nothing, and every iteration of the task
 * failed. The message told the developer to define a bean they had already defined.
 * Marking the class unremovable here keeps Quarkus specifics out of the application code.
 * The same class runs unchanged on Spring Boot, which never removes beans.
 * <p>
 * Collecting the class as one which HAS to be a bean does two things. The platform's
 * validation keeps every collected class unremovable
 * ({@code EnsureCollectedClassesAreBeansBuildStepProcessor}). And it fails the build when
 * the class is no bean at all. So the error "no bean of the resolver class" shows up in the
 * build, and not at the first run of the task.
 */
@Slf4j
public class MultiInstanceResolverBuildStepProcessor {

  private static final String ANNOTATION_ATTRIBUTE_RESOLVER_BEAN = "resolverBean";

  private static final DotName NO_RESOLVER = DotName
      .createSimple(NoResolver.class.getName());

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public MultiInstanceResolverBuildStepProcessor() {
  }

  /**
   * @param combinedIndex The index of the application and of all indexed dependencies
   * @param ensureClassIsBeanBuildItemProducer Producer collecting classes required to be beans
   */
  @BuildStep
  void preserveMultiInstanceResolvers(
      final CombinedIndexBuildItem combinedIndex,
      final BuildProducer<EnsureClassIsBeanValidationBuildItem> ensureClassIsBeanBuildItemProducer) {

    final var resolvers = new LinkedHashSet<DotName>();
    combinedIndex
        .getIndex()
        .getAnnotations(MultiInstanceElement.class)
        .forEach(annotation -> {
          final var resolverBean = annotation.value(ANNOTATION_ATTRIBUTE_RESOLVER_BEAN);
          if (resolverBean == null) {
            // the element is named instead (@MultiInstanceElement("partners")). The
            // core reports the case where both are named
            return;
          }
          final var resolverClass = resolverBean
              .asClass()
              .name();
          if (NO_RESOLVER.equals(resolverClass)) {
            return;
          }
          resolvers.add(resolverClass);
        });

    if (resolvers.isEmpty()) {
      return;
    }

    log.debug("Keeping the multi-instance resolver bean(s) {} - VanillaBP looks them up by class", resolvers);
    resolvers
        .forEach(resolverClass -> {
          ensureClassIsBeanBuildItemProducer
              .produce(EnsureClassIsBeanValidationBuildItem
                  .builder()
                  .className(resolverClass)
                  .usageDescription("Resolver named by @"
                      + MultiInstanceElement.class.getName()
                      + "(resolverBean = ...)")
                  .build());
        });

  }

}
