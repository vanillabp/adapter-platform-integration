package io.vanillabp.integration.deployment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.DotName;
import org.jboss.jandex.ParameterizedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import io.vanillabp.integration.deployment.config.MigrationAdapterProperties;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuilder;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBeanRecorder;
import io.vanillabp.integration.runtime.processservice.TransactionInterceptor;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

public class VanillabpIntegrationProcessor {

  public static final String PREFIX_ADAPTER_PACKAGE = "io.vanillabp.adapter.";
  private static Logger log = LoggerFactory.getLogger(VanillabpIntegrationProcessor.class);

  private static final String FEATURE = "vanillabp";

  public static String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";

  /**
   * Use customized builder for migration adapter properties.
   *
   * @return Build item for migration adapter properties
   */
  @BuildStep
  StaticInitConfigBuilderBuildItem buildMigrationAdapterProperties() {

    return new StaticInitConfigBuilderBuildItem(MigrationAdapterPropertiesBuilder.class);

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
      final MigrationAdapterProperties properties,
      final Capabilities capabilities,
      final BeanArchiveIndexBuildItem indexBuildItem,
      final BuildProducer<FeatureBuildItem> featureProducer,
      final ProcessServiceCdiBeanRecorder processServiceRecorder,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    final var adaptersConfigured = getAndValidateAdaptersConfigured(properties, capabilities);

    LoggerFactory.getLogger(this.getClass()).info("Props: {}", properties.defaultAdapter());

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
                .supplier(processServiceRecorder.processServiceSupplier(workflowAggregateClass))
                .done());
            processServicesBuilt.add(workflowAggregateClass);
          } catch (ClassNotFoundException e) {
            log.debug("NoClassDefFoundError: it might be an optional dependency", e);
          }
        });

  }

  private Map<String, String> getAndValidateAdaptersConfigured(
      final MigrationAdapterProperties properties,
      final Capabilities capabilities) {

    // determine adapters by examining capabilities of Quarkus extensions available:
    final var adapterPackagesProvidedByOtherExtensions = capabilities
        .getCapabilities()
        .stream()
        .filter(capability -> capability.startsWith(PREFIX_ADAPTER_PACKAGE))
        .toList();
    final var adapterNamesProvidedByOtherExtensions = adapterPackagesProvidedByOtherExtensions
        .stream()
        .map(pkg -> pkg.substring(PREFIX_ADAPTER_PACKAGE.length()))
        .toList();
    if (adapterPackagesProvidedByOtherExtensions.isEmpty()) {
      throw new IllegalStateException("No adapters found! Add Quarkus extensions providing VanillaBP adapters.");
    }

    // build result map (key = adapter name, value = adapter type)
    final var result = properties
        .adapters()
        .entrySet()
        .stream()
        .map(config -> Map.entry(config.getKey(), config.getValue().type().orElse(config.getKey())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    // check for unknown adapters
    final var unknownAdapters = result
        .entrySet()
        .stream()
        .filter(adapter -> !adapterNamesProvidedByOtherExtensions.contains(adapter.getValue()))
        .map(adapter -> adapter.getValue()
            + " found in vanillabp.adapters."
            + adapter.getKey())
        .collect(Collectors.joining(", "));
    if (!unknownAdapters.isEmpty()) {
      throw new IllegalStateException("Properties 'vanillabp.adapters.*.type' must contain VanillaBP adapters "
          + "added as Quarkus extension!\nThese adapters are unknown: "
          + unknownAdapters
          + ".\nAvailable adapter types provided by Quarkus extensions currently loaded: "
          + String.join(", ", adapterNamesProvidedByOtherExtensions)
          + ".");
    }

    return result;

  }

}
