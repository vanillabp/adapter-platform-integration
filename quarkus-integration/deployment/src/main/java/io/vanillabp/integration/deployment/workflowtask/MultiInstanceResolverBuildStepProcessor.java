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
 * <code>&#64;MultiInstanceElement(resolverBean = ...)</code> alive (story 71).
 * <p>
 * Such a resolver is a bean nothing injects: it is named in an annotation and VanillaBP
 * asks the container for it while the task runs. Quarkus removes beans nobody injects
 * while it builds the application, so the lookup found nothing and every iteration of
 * the task failed - with a message telling the developer to define a bean they had
 * defined. Marking the class unremovable here keeps the application code free of
 * Quarkus specifics: the same class runs unchanged on Spring Boot, where beans are
 * never removed.
 * <p>
 * Collecting the class as one which HAS to be a bean does both: the platform's
 * validation keeps every collected class unremovable
 * ({@code EnsureCollectedClassesAreBeansBuildStepProcessor}) and fails the build when
 * the class is no bean at all - which moves the "no bean of the resolver class" verdict
 * from the first execution of the task to the build.
 */
@Slf4j
public class MultiInstanceResolverBuildStepProcessor {

  private static final String ANNOTATION_ATTRIBUTE_RESOLVER_BEAN = "resolverBean";

  private static final DotName NO_RESOLVER = DotName
      .createSimple(NoResolver.class.getName());

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
            // the element is named instead (@MultiInstanceElement("partners")) - the
            // core reports the case of naming both
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
