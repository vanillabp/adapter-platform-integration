package io.vanillabp.integration.deployment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.InterceptorBindingRegistrarBuildItem;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.integration.runtime.processservice.TransactionInterceptor;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Main VanillaBP extension processor, responsible for processing configuration
 * and the projects classes during augmentation phase.
 */
public class VanillaBpBuildStepProcessor {

  /**
   * Each VanillaBP adapter is a Quarkus extension publishing a Quarkus extension
   * capability with its name prefixed by this prefix. e.g. io.vanillabp.adapter.dummy
   */
  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";

  /**
   * The VanillaBP extensions feature.
   */
  private static final String FEATURE = "vanillabp";

  /**
   * Build extension feature used as a dependency in VanillaBP adapter extensions.
   *
   * @return The feature build item
   */
  @BuildStep
  FeatureBuildItem buildExtensionFeature() {

    return new FeatureBuildItem(FEATURE);

  }

  /*
  /**
   * Use customized builder for migration adapter properties.
   *
   * @return Build item for migration adapter properties
   * /
  // TODO May be deleted if not needed: to be figured out
  @BuildStep
  StaticInitConfigBuilderBuildItem buildMigrationAdapterProperties() {
  
    return new StaticInitConfigBuilderBuildItem(QuarkusMigrationAdapterPropertiesBuilder.class);
  
  }
   */

  /**
   * Build step for introducing {@link TransactionInterceptor} for all method's
   * annotated by @{@link WorkflowTask}.
   *
   * @param annotationsTransformer {@link TransactionInterceptor}'s annotations need be transformed
   * @param interceptorBindingRegistrarProducer @{@link WorkflowTask} needs to be registered manually
   * @return The additional {@link TransactionInterceptor} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildTransactionInterceptors(
      final BuildProducer<AnnotationsTransformerBuildItem> annotationsTransformer,
      final BuildProducer<InterceptorBindingRegistrarBuildItem> interceptorBindingRegistrarProducer) {

    // Typically an Interceptor needs an Annotation for interceptor binding. Since the
    // annotation @WorkflowTask it is used for is not an interceptor binding annotation
    // it needs to be added programmatically:
    annotationsTransformer.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation
        .forClasses()
        .whenClass(DotName.createSimple(TransactionInterceptor.class.getName()))
        .transform(t -> t.add(WorkflowTask.class))));

    final var annotationMethods = Arrays
        .stream(WorkflowTask.class.getDeclaredMethods())
        .map(Method::getName)
        .collect(Collectors.toSet());
    // Typically an Interceptor needs an Annotation for interceptor binding. Since the
    // annotation @WorkflowTask it is used for is not an interceptor binding annotation
    // the interceptor binding needs to be added programmatically:
    interceptorBindingRegistrarProducer.produce(new InterceptorBindingRegistrarBuildItem(
        new InterceptorBindingRegistrar() {
          @Override
          public List<InterceptorBinding> getAdditionalBindings() {
            return List.of(InterceptorBinding.of(
                WorkflowTask.class,
                // all annotation-values need to be ignored to run the interceptor
                // regardless the value of the annotation
                annotationMethods
            ));
          }
        }
    ));

    // Beans of runtime package need to be registered as additional bean to the index:
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(TransactionInterceptor.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

}
