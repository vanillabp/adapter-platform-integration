package io.vanillabp.integration.deployment;

import java.net.URI;
import java.util.List;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveMarkerBuildItem;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ObjectSubstitutionBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import io.vanillabp.integration.runtime.deployment.VanillaBpShutdownObserver;
import io.vanillabp.integration.runtime.mongo.MongoClientDeploymentProbe;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.runtime.processservice.PhaseTwoOutboxResolverProducer;
import io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer;
import io.vanillabp.integration.runtime.processservice.QuarkusPreCommitRegistrar;
import io.vanillabp.integration.runtime.processservice.TaskDeliveryLogResolverProducer;
import io.vanillabp.integration.runtime.processservice.TransactionRunnerProducer;
import io.vanillabp.integration.runtime.processservice.WorkflowAdapterCacheProducer;
import io.vanillabp.integration.runtime.util.UriSubstitute;
import io.vanillabp.integration.runtime.util.UriSubstitution;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.runtime.workflowtask.WorkflowTaskRegistryProducer;

/**
 * Registers the beans which VanillaBP itself brings to an application. These are the router
 * and the resolvers which the generated process services use, the phase-two outbox, the log
 * of task deliveries, and the beans an adapter announced through its own build item.
 * <p>
 * Which of them an application gets is decided while it is built. Quarkus fixes the set of
 * beans there, not at startup. A bean nobody injects is dropped. So nearly every
 * registration below marks its bean as unremovable. A bean whose class needs an extension
 * which the application does not have is not registered at all. A native image resolves
 * every class it was told about while it is built.
 */
public class VanillaBpBuildStepProcessor {

  /**
   * The VanillaBP extensions feature.
   */
  private static final String FEATURE = "vanillabp";

  /**
   * Quarkus builds this processor while it augments the application and calls the build
   * steps below on it. Nothing else builds it, and no step keeps state in it.
   */
  public VanillaBpBuildStepProcessor() {
  }

  /**
   * Builds the extension feature. VanillaBP adapter extensions use it as a dependency.
   *
   * @return The feature build item
   */
  @BuildStep
  FeatureBuildItem buildExtensionFeature() {

    return new FeatureBuildItem(FEATURE);

  }

  /**
   * Registers the workflow-module descriptor as an application-archive marker. So
   * workflow-module JARs count as application archives, even if they have no Jandex index.
   * Examples are Gradle modules, or JARs with only the descriptor and BPMS resources. A
   * Jandex index is still needed to find classes annotated with
   * {@link io.vanillabp.spi.service.WorkflowService} inside sub-modules. It is not needed to
   * detect the workflow module itself.
   *
   * @return The additional application-archive marker build item
   */
  @BuildStep
  AdditionalApplicationArchiveMarkerBuildItem workflowModuleArchiveMarker() {

    return new AdditionalApplicationArchiveMarkerBuildItem(WorkflowModule.METAINF_WORKFLOWMODULE);

  }

  /**
   * Quarkus cannot serialize every object as it is. For such an object, it needs an
   * ObjectSubstitutionBuildItem to serialize and deserialize it. {@link URI} is such an
   * object.
   *
   * @see io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleBuildStepProcessor#findAllWorkflowModules(ApplicationArchivesBuildItem)
   * @return An object substitution build item for {@link URI}
   */
  @BuildStep
  ObjectSubstitutionBuildItem uriSubstitution() {

    return new ObjectSubstitutionBuildItem(URI.class, UriSubstitute.class, UriSubstitution.class);

  }

  /**
   * Registers the adapters' process-service beans announced by
   * {@link VanillaBpMigratableProcessServiceBuildItem}. An adapter only produces the build
   * item (adapter type and bean class). The VanillaBP extension registers the bean, so the
   * adapter does not need to register it itself.
   *
   * @param processServicesProvidedByAdapters The build items produced by the adapters
   * @param additionalBeans Producer used to register the announced beans
   */
  @BuildStep
  void buildAdapterProcessServiceBeans(
      final List<VanillaBpMigratableProcessServiceBuildItem> processServicesProvidedByAdapters,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    processServicesProvidedByAdapters
        .stream()
        .map(VanillaBpMigratableProcessServiceBuildItem::getMigratableProcessServiceBeanClass)
        .filter(beanClass -> (beanClass != null) && !beanClass.isBlank())
        .forEach(beanClass -> additionalBeans.produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(beanClass)
            .setUnremovable() // don't remove, since it is used under the hoods
            .build()));

  }

  /**
   * Stops ArC from removing the adapters' <code>List</code> beans. An adapter produces ONE
   * bean of type <code>List&lt;MigratableProcessService&gt;</code> or
   * <code>List&lt;AdapterDeploymentService&gt;</code>, with one instance for each configured
   * adapter id. A CDI producer cannot return N element beans for N ids which are only known
   * at runtime. The platform collects these lists only by
   * <code>Instance&lt;List&lt;...&gt;&gt;</code> lookups. Without this build item, ArC
   * treats the producers as unused and removes them.
   * <p>
   * Convention (part of the contract for one instance per adapter id): the element type of
   * the List is the SPI interface itself (e.g.
   * <code>List&lt;MigratableProcessService&lt;Object&gt;&gt;</code>), not an
   * adapter-specific subclass. This method matches the type literally.
   *
   * @return The unremovable-bean build item
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem keepPerAdapterIdListBeans() {

    final var listName = org.jboss.jandex.DotName.createSimple(List.class.getName());
    final var elementTypes = java.util.Set.of(
        org.jboss.jandex.DotName.createSimple(
            io.vanillabp.integration.adapter.spi.MigratableProcessService.class.getName()),
        org.jboss.jandex.DotName.createSimple(
            io.vanillabp.integration.adapter.spi.AdapterDeploymentService.class.getName()));

    return new io.quarkus.arc.deployment.UnremovableBeanBuildItem(
        beanInfo -> beanInfo
            .getTypes()
            .stream()
            .anyMatch(type -> (type.kind() == org.jboss.jandex.Type.Kind.PARAMETERIZED_TYPE) && type.name()
                .equals(listName) && (type.asParameterizedType().arguments().size() == 1) && elementTypes
                    .contains(type.asParameterizedType().arguments().getFirst().name())));

  }

  /**
   * Registers the phase-two router which the core owns (through
   * {@link PhaseTwoRouterProducer}). The generated process-service beans register
   * themselves with it when they are created. The phase-two outbox dispatches committed
   * entries through it. It is registered no matter which outbox implementation is used. So
   * custom <code>PhaseTwoOutbox</code> beans can rely on it, too.
   *
   * @return The additional {@link PhaseTwoRouterProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPhaseTwoRouter() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PhaseTwoRouterProducer.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers the resolver which says which phase-two outbox an aggregate's transaction
   * reaches (through {@link PhaseTwoOutboxResolverProducer}). The process services find
   * their own outbox through it. An extension which writes its own entries injects it. It
   * does not pick an outbox bean itself, because it cannot tell which one is right.
   *
   * @return The additional {@link PhaseTwoOutboxResolverProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPhaseTwoOutboxResolver() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PhaseTwoOutboxResolverProducer.class)
        .setUnremovable() // an extension may be the only one injecting it
        .build();

  }

  /**
   * Registers the resolver which says which log of processed task deliveries an aggregate's
   * transaction reaches (through {@link TaskDeliveryLogResolverProducer}). The process
   * services find their own log through it. An extension which reads what a workflow is
   * waiting for injects it. It does not pick a log bean itself, because it cannot tell which
   * one is right.
   *
   * @return The additional {@link TaskDeliveryLogResolverProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildTaskDeliveryLogResolver() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(TaskDeliveryLogResolverProducer.class)
        .setUnremovable() // an extension may be the only one injecting it
        .build();

  }

  /**
   * Registers the transaction of VanillaBP as beans (through
   * {@link TransactionRunnerProducer}). These are the platform's own runner and the resolver
   * which says through which runner a workflow aggregate is written. The process services,
   * the pre-commit registrar and the phase-two router use them. An extension may write
   * something of its own into the transaction of an aggregate. It injects the resolver and
   * does not open a transaction of its own.
   *
   * @return The additional {@link TransactionRunnerProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildTransactionRunner() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(TransactionRunnerProducer.class)
        .setUnremovable() // an extension may be the only one injecting it
        .build();

  }

  /**
   * Registers the default in-memory election cache. It keeps which adapter the BPMS election
   * chose for each workflow (see the core's <code>WorkflowLocator</code>). A
   * <code>WorkflowAdapterCache</code> bean of the application replaces the default, because
   * the default is a <code>&#64;DefaultBean</code>.
   *
   * @return The additional {@link WorkflowAdapterCacheProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildWorkflowAdapterCache() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(WorkflowAdapterCacheProducer.class)
        .setUnremovable() // resolved via Instance lookup only
        .build();

  }

  /**
   * Registers the registry of <code>&#64;WorkflowTask</code> handlers which the core owns.
   * Adapters see it as
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}. The
   * generated process-service beans register every workflow service class under all BPMN
   * process IDs it declares. Adapters check the wiring during <code>wireBpmn</code> and call
   * the task handlers at runtime.
   *
   * @return The additional {@link WorkflowTaskRegistryProducer} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildWorkflowTaskRegistry() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(WorkflowTaskRegistryProducer.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers the default implementations of the phase-two outbox (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}), which two-phase workflow starts
   * use. The one based on JDBC and Agroal is registered if a JDBC datasource applies. The one
   * based on MongoDB is registered if the <code>quarkus-mongodb-client</code> extension is
   * present. Both may exist side by side. Each workflow aggregate uses the outbox which
   * matches its persistence. {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware} beans
   * say which one that is, see
   * {@link io.vanillabp.integration.runtime.processservice.QuarkusPhaseTwoOutboxResolver}.
   * To turn off a default you do not want, use <code>vanillabp.outbox.jdbc.enabled</code> or
   * <code>vanillabp.outbox.mongo.enabled</code>. Applications may always add their own
   * <code>PhaseTwoOutbox</code> beans.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the outbox beans
   */
  @BuildStep
  void buildPhaseTwoOutbox(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (capabilities.isPresent(Capability.AGROAL)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(
              JdbcPhaseTwoOutbox.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

    if (capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(
              MongoPhaseTwoOutbox.class,
              MongoPhaseTwoOutboxDispatcher.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

  }

  /**
   * Registers the probe which says whether the MongoDB deployment is a replica set (see
   * {@link io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe}). The
   * startup check of the transaction runner needs it for an aggregate which MongoDB Panache
   * manages.
   * <p>
   * The MongoDB client capability guards it, like the outbox and the delivery log. There is
   * one more reason here. The implementation is the only class on the way to the coverage
   * result which names a MongoDB type. A native image resolves every referenced method while
   * it is built. So an application with a relational database and no MongoDB must not reach
   * it at all.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the probe
   */
  @BuildStep
  void buildMongoDeploymentProbe(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (!capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      return;
    }
    additionalBeans.produce(AdditionalBeanBuildItem
        .builder()
        .addBeanClass(MongoClientDeploymentProbe.class)
        .setUnremovable() // don't remove, since it is looked up under the hoods
        .build());

  }

  /**
   * Registers the default implementations of the log of processed task deliveries (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The one based on JDBC and Agroal
   * is registered if a JDBC datasource applies. The one based on MongoDB is registered if the
   * <code>quarkus-mongodb-client</code> extension is present. Both may exist side by side.
   * Each workflow aggregate uses the log which matches its persistence.
   * {@link io.vanillabp.integration.spi.TaskDeliveryLogAware} beans say which one that is,
   * see {@link io.vanillabp.integration.runtime.processservice.QuarkusTaskDeliveryLogResolver}.
   * To turn off a default you do not want, use <code>vanillabp.outbox.jdbc.enabled</code> or
   * <code>vanillabp.outbox.mongo.enabled</code>. The log shares the store settings of the
   * outbox.
   *
   * @param capabilities Capabilities of the project's extensions
   * @param additionalBeans Producer used to register the delivery-log beans
   */
  @BuildStep
  void buildTaskDeliveryLog(
      final Capabilities capabilities,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (capabilities.isPresent(Capability.AGROAL)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

    if (capabilities.isPresent(Capability.MONGODB_CLIENT)) {
      additionalBeans.produce(AdditionalBeanBuildItem
          .builder()
          .addBeanClasses(io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog.class)
          .setUnremovable() // don't remove, since it is used under the hoods
          .build());
    }

  }

  /**
   * Beans which implement {@link io.vanillabp.integration.spi.TaskDeliveryLog} or
   * {@link io.vanillabp.integration.spi.TaskDeliveryLogAware} are looked up at runtime, to
   * find the log of each aggregate. Application code does not inject them. So ArC must not
   * remove them as unused.
   *
   * @return The unremovable-bean build item covering delivery logs and their attributions
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem preserveTaskDeliveryLogBeans() {

    return io.quarkus.arc.deployment.UnremovableBeanBuildItem.beanTypes(
        io.vanillabp.integration.spi.TaskDeliveryLog.class,
        io.vanillabp.integration.spi.TaskDeliveryLogAware.class);

  }

  /**
   * Application code does not always inject beans which implement
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} or
   * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}. They are looked up at runtime,
   * to find the outbox of each aggregate. This build step stops ArC from removing them as
   * unused beans.
   *
   * @return The unremovable-bean build item covering outboxes and their attributions
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem preservePhaseTwoOutboxBeans() {

    return io.quarkus.arc.deployment.UnremovableBeanBuildItem.beanTypes(
        io.vanillabp.integration.spi.PhaseTwoOutbox.class,
        io.vanillabp.integration.spi.PhaseTwoOutboxAware.class);

  }

  /**
   * Registers {@link VanillaBpShutdownObserver} as a CDI bean. On a graceful shutdown, it
   * stops the workflow processing of adapters and extensions. It is the Quarkus counterpart
   * of <code>SmartLifecycle.stop()</code> in the Spring Boot integration. It is marked
   * unremovable, because application code does not inject it. The
   * {@link io.quarkus.runtime.ShutdownEvent} drives it.
   *
   * @return The additional {@link VanillaBpShutdownObserver} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildShutdownObserver() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(VanillaBpShutdownObserver.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Registers {@link QuarkusPreCommitRegistrar} as a CDI bean. BPMS adapters hand their
   * phase-one checks to it. So the check runs right before the transaction of the workflow
   * aggregate commits. It is unremovable, because no application code injects it.
   *
   * @return The additional {@link QuarkusPreCommitRegistrar} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildPreCommitRegistrar() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(QuarkusPreCommitRegistrar.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

}
