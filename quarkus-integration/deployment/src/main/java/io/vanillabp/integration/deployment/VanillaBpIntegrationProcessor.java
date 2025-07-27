package io.vanillabp.integration.deployment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.InterceptorBindingRegistrarBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem;
import io.vanillabp.integration.deployment.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.deployment.config.QuarkusMigrationAdapterTransformer;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBeanRecorder;
import io.vanillabp.integration.runtime.processservice.TransactionInterceptor;
import io.vanillabp.integration.runtime.processservice.config.QuarkusMigrationAdapterPropertiesBuilder;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VanillaBpIntegrationProcessor {

  /**
   * Each VanillaBP adapter is a Quarkus extension publishing a Quarkus extension
   * capability with its name prefix by this prefix. e.g. io.vanillabp.adapter.dummy
   */
  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";

  private static final String FEATURE = "vanillabp";

  public static String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";

  /**
   * Use customized builder for migration adapter properties.
   *
   * @return Build item for migration adapter properties
   */
  @BuildStep
  StaticInitConfigBuilderBuildItem buildMigrationAdapterProperties() {

    return new StaticInitConfigBuilderBuildItem(QuarkusMigrationAdapterPropertiesBuilder.class);

  }

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

  @Record(ExecutionTime.STATIC_INIT)
  @BuildStep
  void buildProcessServices(
      final QuarkusMigrationAdapterProperties properties,
      final Capabilities capabilities,
      final BeanArchiveIndexBuildItem indexBuildItem,
      final BuildProducer<FeatureBuildItem> featureProducer,
      final ProcessServiceCdiBeanRecorder processServiceRecorder,
      final List<VanillaBpMigratableProcessServiceBuildItem> processServiceBuildItems,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanProducer/* ,
                                                                       final BeanContainer beanContainer */) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    // check for consistent configuration and required VanillaBpMigratableProcessServiceBuildItems
    final var adapterProperties = QuarkusMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .capabilities(capabilities)
        .build()
        .getAndValidatePropertiesConfigured(processServiceBuildItems);

    // scan for bean annotated by @WorkflowService
    final Set<Class<?>> processServicesBuilt = new HashSet<>();
    indexBuildItem
        .getIndex()
        .getAnnotations(WorkflowService.class)
        // and build an adapter aware process service for each of them
        .forEach(annotation -> {
          try {
            final var serviceClass = annotation.target();
            final var workflowAggregateType = annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
                .asClass();
            final var workflowAggregateClass = getClass().getClassLoader()
                .loadClass(workflowAggregateType.name().toString());
            if (processServicesBuilt.contains(workflowAggregateClass)) {
              return;
            }
            syntheticBeanProducer.produce(SyntheticBeanBuildItem
                .configure(ProcessService.class)
                .types(ParameterizedType.create(ProcessService.class, workflowAggregateType))
                .scope(Singleton.class)
                .supplier(processServiceRecorder.processServiceSupplier(
                    workflowAggregateClass,
                    adapterProperties,
                    // TODO Fill services argument
                    List.of()))
                .done());
            processServicesBuilt.add(workflowAggregateClass);
          } catch (ClassNotFoundException e) {
            log.debug("NoClassDefFoundError: it might be an optional dependency", e);
          }
        });

  }

}
