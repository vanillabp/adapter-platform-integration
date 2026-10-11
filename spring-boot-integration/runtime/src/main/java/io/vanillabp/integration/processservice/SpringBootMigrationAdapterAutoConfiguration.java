package io.vanillabp.integration.processservice;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.ClasspathFacts;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.health.AdapterHealthReport;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.health.VanillaBpHealthIndicator;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.support.BpmsAdapters;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.integration.workflowtask.SpringTransactionAnnotations;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;
import io.vanillabp.spi.process.ProcessService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP adapters. The {@link ProcessServiceBeanRegistrar}
 * registers the {@link io.vanillabp.spi.process.ProcessService} beans, once
 * {@link WorkflowServiceDiscovery} knows which workflow services this application brings.
 */
@Slf4j
@AutoConfiguration(after = WorkflowModuleAutoConfiguration.class)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  static final String BEANNAME_MIGRATIONADAPERPROPERTIES = "VanillaBpMigrationAdapterProperties";

  /**
   * Built by Spring Boot. This autoconfiguration only reaches an application because a BPMS
   * adapter brought it. So it runs exactly where there is one. Several bean methods here
   * are static on purpose. So what they build is available without creating this class
   * first.
   */
  public SpringBootMigrationAdapterAutoConfiguration() {

  }

  /**
   * Finds the workflow services among the bean definitions of the application and
   * registers a {@link io.vanillabp.spi.process.ProcessService} bean per workflow
   * aggregate.
   * <p>
   * It is static. A bean-factory post-processor which comes from an instance method would
   * force this configuration class, and everything it depends on, to be created before
   * the other post-processors ran.
   *
   * @return The discovery
   */
  @Bean
  public static WorkflowServiceDiscovery vanillaBpWorkflowServiceDiscovery() {

    return new WorkflowServiceDiscovery();

  }

  /**
   * Converts the values of <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>
   * into the core enum, ignoring case. It is registered as a
   * {@link ConfigurationPropertiesBinding} converter. So an invalid value fails the binding
   * with a message which names the allowed values. Spring's bind failure adds the property
   * key and the wrong value.
   *
   * @return The converter
   */
  @Bean
  @ConfigurationPropertiesBinding
  public static Converter<String, DeploymentFailurePolicy> vanillaBpDeploymentFailurePolicyConverter() {

    return source -> {
      try {
        return DeploymentFailurePolicy.valueOf(source.trim().toUpperCase());
      } catch (final IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "must be one of 'fail' or 'warn'");
      }
    };

  }

  /**
   * Validates the directly bound {@link VanillaBpConfigurationProperties}, with the facts
   * from the classpath passed into the core validation. Then it provides them as the
   * {@link MigrationAdapterProperties} bean, which does not know the platform. The common
   * adapter implementation of module "migration-adapter" uses that bean.
   *
   * @param properties The bound core properties
   * @param environment The Spring environment. It is used to detect environment variables
   *          which the binding did not take over
   * @param allWorkflowModules All workflow modules found in classpath
   * @param adapterConfigurations Configuration beans of adapters found in classpath
   * @param applicationContext Used to find the classpath root of the
   *          <code>&#64;SpringBootApplication</code> class. That root decides whether a
   *          workflow module IS the application or is shipped as its own artifact. And
   *          that decides where its BPMN files are read from
   * @return The validated properties bean
   */
  @Bean(BEANNAME_MIGRATIONADAPERPROPERTIES)
  @Primary
  public static MigrationAdapterProperties migrationAdapterProperties(
      final VanillaBpConfigurationProperties properties,
      final Environment environment,
      final WorkflowModules allWorkflowModules,
      final ObjectProvider<AdapterConfigurationBase> adapterConfigurations,
      final ApplicationContext applicationContext) {

    // ObjectProvider, not a required List. Without any adapter on the classpath, the bean
    // creation still runs, and the guiding message about a missing adapter can really be
    // reached
    final var adaptersLoaded = adapterConfigurations
        .stream()
        .map(AdapterConfigurationBase::getAdapterType)
        .toList();
    if (adaptersLoaded.isEmpty()) {
      // NoBpmsAdapterCheck of module 'vanillabp-spring-boot-support' reports the
      // neighbouring case: no integration at all, because the adapter which would have
      // brought it is missing
      throw new IllegalStateException(
          """
              No VanillaBP BPMS adapter found in classpath! VanillaBP's Spring Boot integration is \
              loaded, but no adapter, so there is no BPMS which could run the workflows of a \
              workflow module.%s"""
              .formatted(BpmsAdapters.artifactsToAdd()));
    }

    // convention over configuration: the conventions are derived from the classpath
    // facts. Which workflow module descriptor comes from the application's MAIN artifact
    // decides the conventional resources location
    final var mainArtifactRootPrefix = mainArtifactRootPrefix(applicationContext);
    final var workflowModules = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(workflowModule -> new ClasspathFacts.WorkflowModuleInfo(
            workflowModule.getId(), workflowModule.isDeclaredInTheArtifactOf(mainArtifactRootPrefix)))
        .toList();

    properties.validateProperties(
        new ClasspathFacts(adaptersLoaded, workflowModules),
        null);
    properties.validateEnvironmentVariableUsage(rawPropertyNames(environment));

    return properties;

  }

  /**
   * Where an adapter says what it found while the application starts.
   * <p>
   * The checks of the core report into the same collection. So an adapter's finding stands
   * in the same block as the platform's. The collection hangs on the configuration,
   * because every check already holds the configuration. This bean is the way to it for
   * everything which does not.
   *
   * @param properties The VanillaBP configuration, which carries the collection
   * @return Where a startup check of an adapter reports
   */
  @Bean
  @ConditionalOnMissingBean
  public static io.vanillabp.integration.spi.startup.StartupReport vanillaBpStartupReport(
      @org.springframework.beans.factory.annotation.Qualifier(
        BEANNAME_MIGRATIONADAPERPROPERTIES) final MigrationAdapterProperties properties) {

    return properties.startupFindings();

  }

  /**
   * The sync model the core owns. It turns a workflow aggregate into the values shared
   * with the BPMS. It honors {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the
   * adapter's default. There is one instance per application. BPMS adapters receive it and
   * decide what to do with the values: push them as process variables, or only write them
   * as operator context.
   *
   * @param properties The VanillaBP configuration, which carries the collection every
   *          startup check reports into
   * @return The sync support
   */
  @Bean
  @ConditionalOnMissingBean
  public static WorkflowAggregateSync vanillaBpWorkflowAggregateSync(
      @Qualifier(BEANNAME_MIGRATIONADAPERPROPERTIES) final MigrationAdapterProperties properties) {

    return new AggregateSyncSupport(properties.startupFindings());

  }

  /**
   * The classpath-root prefix of the application's MAIN artifact: the JAR or directory the
   * <code>&#64;SpringBootApplication</code> class was loaded from. A workflow module
   * descriptor found in that very root belongs to the application itself, not to a
   * workflow module shipped as its own artifact. That decides the conventional resources
   * location.
   * <p>
   * Only bean TYPES are inspected, never instances. So nothing is created early. An
   * application without such a class (e.g. a plain <code>&#64;Configuration</code> test
   * context) gets <code>null</code>. Then every workflow module is treated as an artifact
   * of its own, which is the safe choice.
   *
   * @param applicationContext The application context
   * @return The classpath-root prefix or <code>null</code>
   */
  private static String mainArtifactRootPrefix(
      final ApplicationContext applicationContext) {

    return Arrays
        .stream(applicationContext.getBeanNamesForAnnotation(SpringBootApplication.class))
        .map(applicationContext::getType)
        .filter(Objects::nonNull)
        .map(ClassUtils::getUserClass)
        .map(WorkflowModuleAutoConfiguration::determineClasspathRootPrefix)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * Collects the raw names of all enumerable properties of the environment. That includes
   * the unconverted environment-variable names of the <code>systemEnvironment</code>
   * property source. The core uses them to detect <code>VANILLABP_*</code> variables which
   * the binding did not take over.
   *
   * @param environment The Spring environment
   * @return The raw property names
   */
  private static List<String> rawPropertyNames(
      final Environment environment) {

    final var result = new LinkedList<String>();
    if (environment instanceof AbstractEnvironment abstractEnvironment) {
      abstractEnvironment
          .getPropertySources()
          .stream()
          .filter(EnumerablePropertySource.class::isInstance)
          .map(EnumerablePropertySource.class::cast)
          .forEach(propertySource -> result.addAll(List.of(propertySource.getPropertyNames())));
    }
    return result;

  }

  /**
   * The router the core owns. It receives the phase-two calls which a
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementation dispatches. It
   * routes them to the {@link io.vanillabp.spi.process.ProcessService} bean which is
   * responsible for the given workflow module and BPMN process. The process-service beans
   * register themselves (including the aggregate-ID converter) when they are created.
   *
   * @param metrics What the router counts its dispatches into. Absent where the
   *          application brings no metrics backend
   * @return The phase-two router
   */
  @Bean
  @ConditionalOnMissingBean(PhaseTwoRouter.class)
  public PhaseTwoRouter vanillaBpPhaseTwoRouter(
      final ObjectProvider<VanillaBpMetrics> metrics) {

    final var router = new PhaseTwoRouter();
    router.setMetrics(vanillaBpMetricsOf(metrics));
    return router;

  }

  /**
   * The metrics implementation to use: the Micrometer one where the application brings
   * Micrometer, and otherwise
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}.
   * Micrometer is optional, so the bean may be absent.
   *
   * @param metrics The provider of the metrics bean
   * @return What to record into, never <code>null</code>
   */
  public static VanillaBpMetrics vanillaBpMetricsOf(
      final ObjectProvider<VanillaBpMetrics> metrics) {

    return metrics
        .getIfAvailable(
            () -> VanillaBpMetrics.NONE);

  }

  /**
   * The router's registry of phase-two operations. It can be injected, so an extension can
   * register operations of its own. The router itself registers VanillaBP's core
   * operations.
   * <p>
   * On purpose, this is NOT conditional on a missing bean. The registry in which an
   * extension registers has to be the one the router dispatches from. An application which
   * wants its own registry gives it to its own {@link PhaseTwoRouter} bean, and this bean
   * follows.
   *
   * @param phaseTwoRouter The router owning the registry
   * @return The operation registry
   */
  @Bean
  public PhaseOperationRegistry vanillaBpPhaseOperationRegistry(
      final PhaseTwoRouter phaseTwoRouter) {

    return phaseTwoRouter.getOperations();

  }

  /**
   * The cache of workflow&rarr;adapter associations. The BPMS election asks it for
   * operations on existing workflows (complete/cancel task, user task, message
   * correlation). This is the in-memory default. It is bounded, its entries expire, and
   * <code>vanillabp.workflow-adapter-cache.*</code> sizes it. Cluster setups share their
   * elections instead. They use the implementation VanillaBP ships for Hazelcast, or a
   * bean of their own which implements
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache}. Either replaces this
   * default. Entries are hints: a stale entry costs an extra probe, never correctness.
   * <p>
   * This cache reports what it knows about itself, its size and its evictions, under a
   * prefix of this implementation. It does not get the application's election statistics
   * for that. A number which only one implementation can produce does not belong among
   * the numbers of the election.
   *
   * @param properties The VanillaBP configuration (the cache's bounds)
   * @return The default in-memory cache
   */
  @Bean
  @ConditionalOnMissingBean(WorkflowAdapterCache.class)
  public WorkflowAdapterCache vanillaBpWorkflowAdapterCache(
      final MigrationAdapterProperties properties) {

    return new InMemoryWorkflowAdapterCache(
        properties.getWorkflowAdapterCache());

  }

  /**
   * What the election asked of the cache: hits, misses, and how often the end of a
   * workflow reached it. There is one instance per application, and the same three numbers
   * for every implementation. The process services wrap whatever cache is in use into an
   * {@code InstrumentedWorkflowAdapterCache} which reports here. So the numbers stay
   * comparable when an application plugs in its own cache.
   *
   * @return The statistics
   */
  @Bean
  @ConditionalOnMissingBean
  public WorkflowAdapterCacheStatistics vanillaBpWorkflowAdapterCacheStatistics() {

    return new WorkflowAdapterCacheStatistics();

  }

  /**
   * Publishes the election cache's statistics as Micrometer meters, if the application
   * brings Micrometer. The Actuator's metrics auto-configuration applies
   * {@link io.micrometer.core.instrument.binder.MeterBinder} beans to every registry. So no
   * endpoint of our own is needed. An application without Micrometer boots unchanged and
   * reports no metrics.
   */
  @Configuration(proxyBeanMethods = false)
  // by NAME, not by class literal. The annotation of a nested configuration class is
  // read by reflection. So a class literal of an optional dependency which is missing
  // would fail before the condition is even evaluated
  @ConditionalOnClass(
      name = "io.micrometer.core.instrument.MeterRegistry")
  public static class WorkflowAdapterCacheMetricsConfiguration {

    /**
     * Built by Spring where the condition above holds, which is where Micrometer is on the
     * classpath.
     */
    public WorkflowAdapterCacheMetricsConfiguration() {

    }

    /**
     * Publishes what the election asked of the cache as Micrometer meters, whichever cache
     * the application ended up with. For exactly that reason, the numbers are counted
     * outside the cache. So they stay comparable.
     *
     * @param statistics The application's cache statistics
     * @return The meter binder of the election cache
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowAdapterCacheMeters vanillaBpWorkflowAdapterCacheMeters(
        final WorkflowAdapterCacheStatistics statistics) {

      return new WorkflowAdapterCacheMeters(
          statistics);

    }

    /**
     * What the in-memory cache knows about itself, published under the prefix of that
     * implementation. The binder takes the cache the application ended up with. It
     * registers nothing where that is a shared cache or one of the application's own. A
     * size which cannot be read is a meter which is not published. That is better than one
     * which reports NaN for the rest of the application's life.
     *
     * @param caches The election caches of this application. The platform's default and
     *          the bean an application defines can exist side by side.
     *          {@link WorkflowAdapterCacheSelection} decides which of them the elections
     *          use, not an injection
     * @return The meter binder of the in-memory cache
     */
    @Bean
    @ConditionalOnMissingBean
    public InMemoryWorkflowAdapterCacheMeters vanillaBpInMemoryWorkflowAdapterCacheMeters(
        final ObjectProvider<WorkflowAdapterCache> caches) {

      return new InMemoryWorkflowAdapterCacheMeters(
          WorkflowAdapterCacheSelection
              .theCacheInUse(caches
                  .orderedStream()
                  .toList()));

    }

    /**
     * What VanillaBP counts about its deliveries and its outbox. It is a
     * {@code MeterBinder} as well. So the Actuator hands it the registry the same way it
     * hands it to the election cache's meters. Nothing of VanillaBP asks for a registry
     * itself.
     *
     * @param properties The VanillaBP configuration. It says how long the measurement of a
     *          gauge which has to ask somebody is reused
     * @return The metrics of deliveries and outbox
     */
    @Bean
    @ConditionalOnMissingBean
    public MicrometerVanillaBpMetrics vanillaBpMetrics(
        final MigrationAdapterProperties properties) {

      return new MicrometerVanillaBpMetrics(
          properties
              .getMetrics()
              .resolvedGaugeCache());

    }

    /**
     * Publishes what each outbox store says about its backlog: how many entries wait
     * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#pendingCalls()}) and how long
     * the oldest of them has been waiting
     * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#ageOfOldestPendingCall()}).
     * Each gauge is published for the stores which can answer its question. The two
     * questions are asked separately, because a store may answer one of them and not the
     * other. The gauges are tagged with the name of the store, because an application may
     * run several stores (mixed persistence, a dedicated outbox for one aggregate).
     * <p>
     * The stores are looked up lazily. Touching them while the beans are built would
     * create persistence infrastructure long before the application asked for it.
     *
     * @param metrics The metrics to register the gauges with
     * @param outboxes All outbox stores of the application
     * @return The registration, run once the context is ready
     */
    @Bean
    public SmartInitializingSingleton vanillaBpOutboxBacklogGauges(
        final MicrometerVanillaBpMetrics metrics,
        final ObjectProvider<PhaseTwoOutbox> outboxes) {

      return () -> outboxes
          .stream()
          .forEach(outbox -> {
            final var store = outbox
                .getClass()
                .getSimpleName();
            if (outbox
                .pendingCalls()
                .isPresent()) {
              metrics.registerPendingOutboxEntries(store, outbox::pendingCalls);
            }
            if (outbox
                .ageOfOldestPendingCall()
                .isPresent()) {
              metrics.registerAgeOfOldestPendingOutboxEntry(store, outbox::ageOfOldestPendingCall);
            }
          });

    }

  }

  /**
   * Asks every BPMS adapter what it can say about its BPMS. This is the half of the health
   * contribution which does not know the platform. The adapters are looked up on every
   * call. So an adapter which is created late is asked as well.
   *
   * @param deploymentServices The adapters' deployment services, one per configured
   *          adapter id
   * @return The report
   */
  @Bean
  @ConditionalOnMissingBean
  public AdapterHealthReport vanillaBpAdapterHealthReport(
      final ObjectProvider<AdapterDeploymentService<?, ?>> deploymentServices) {

    return new AdapterHealthReport(
        () -> deploymentServices
            .stream()
            .toList());

  }

  /**
   * Publishes the adapters' health under the component <code>vanillabp</code> of
   * Spring Boot's health endpoint, where the application brings the health support.
   * An application without it boots unchanged and publishes nothing.
   */
  @Configuration(proxyBeanMethods = false)
  // by NAME, for the same reason the metrics configuration uses names: the annotation of
  // a nested configuration class is read by reflection
  @ConditionalOnClass(
      name = "org.springframework.boot.health.contributor.HealthIndicator")
  public static class AdapterHealthConfiguration {

    /**
     * Built by Spring where the condition above holds, which is where Spring Boot's health
     * support is on the classpath.
     */
    public AdapterHealthConfiguration() {

    }

    /**
     * The bean name decides the name of the health component, so it has to stay
     * <code>vanillabp</code> + the suffix Spring Boot strips.
     *
     * @param report What the adapters answered
     * @return The health indicator
     */
    @Bean("vanillabpHealthIndicator")
    @ConditionalOnMissingBean(name = "vanillabpHealthIndicator")
    public VanillaBpHealthIndicator vanillabpHealthIndicator(
        final AdapterHealthReport report) {

      return new VanillaBpHealthIndicator(report);

    }

  }

  /**
   * The name-clash-avoidance model the core owns. It finds the mode per workflow module or
   * workflow and adapter, and it builds the identifiers a BPMS sees. There is one instance
   * per application. BPMS adapters receive it and apply it to their model and their
   * commands.
   * <p>
   * The adapters' deployment services are streamed LAZILY. They receive this bean, so they
   * cannot be injected here. Without configuration, the adapter's own mode applies. The
   * adapter itself reports a workflow module which is not scoped.
   *
   * @param properties The VanillaBP configuration
   * @param deploymentServices The adapters' deployment services, resolved on first use
   * @return The name-clash-avoidance support
   */
  @Bean
  @ConditionalOnMissingBean
  public NameClashAvoidanceSupport vanillaBpNameClashAvoidanceSupport(
      final MigrationAdapterProperties properties,
      final ObjectProvider<AdapterDeploymentService<?, ?>> deploymentServices) {

    return new NameClashAvoidanceService(
        properties, () -> deploymentServices
            .stream()
            .toList());

  }

  /**
   * The registry the core owns for <code>&#64;WorkflowTask</code> handlers, and the
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker} the
   * adapters use. The process-service beans register every workflow service class under
   * all BPMN process IDs it declares. Adapters validate the wiring during
   * <code>wireBpmn</code>, and they dispatch task invocations at runtime. Handlers run
   * through the Spring transaction runner. It looks up the
   * {@link org.springframework.transaction.PlatformTransactionManager} lazily. So an
   * application without transactional persistence still boots, and gets a guiding
   * message when the first task is processed.
   *
   * @param platformTransactionRunner The platform's transaction runner, which looks up the
   *          transaction manager lazily
   * @param aggregateSync The core's sync model. It is validated per registered
   *          workflow-aggregate class at startup. And it answers the shared values of an
   *          aggregate which an adapter does not hold
   * @param properties The VanillaBP configuration
   * @param scoping The core's name-clash-avoidance support, which judges the identifiers
   *          of the versions a BPMS still holds
   * @return The workflow-task registry
   */
  @Bean
  public WorkflowTaskRegistry vanillaBpWorkflowTaskRegistry(
      final SpringTransactionRunner platformTransactionRunner,
      final WorkflowAggregateSync aggregateSync,
      @Qualifier(
        BEANNAME_MIGRATIONADAPERPROPERTIES) final MigrationAdapterProperties properties,
      final NameClashAvoidanceSupport scoping) {

    return new WorkflowTaskRegistry(
        platformTransactionRunner, aggregateSync, SpringTransactionAnnotations
            .specs(), properties, scoping);

  }

  /**
   * The handler methods of the extensions. An extension registers its own annotation here
   * and invokes the methods which carry it. It is the registry's own object, not a second
   * one, because the methods are scanned in the same registration call as the
   * <code>&#64;WorkflowTask</code> methods.
   *
   * @param workflowTaskRegistry The registry owning it
   * @return The extension-facing handler service
   */
  @Bean
  public ExtensionHandlers vanillaBpExtensionHandlers(
      final WorkflowTaskRegistry workflowTaskRegistry) {

    return workflowTaskRegistry.getExtensionHandlers();

  }

  /**
   * The election, offered to extensions: which BPMS holds a given workflow right now. An
   * extension which addressed the first-priority adapter instead would talk to the wrong
   * BPMS for every workflow which a migration has already moved.
   *
   * @param phaseTwoRouter The router holding the process services of this application
   * @return The extension-facing election
   */
  @Bean
  public WorkflowElection vanillaBpWorkflowElection(
      final PhaseTwoRouter phaseTwoRouter) {

    return new ExtensionWorkflowElection(phaseTwoRouter);

  }

  /**
   * The platform's transaction runner. It is used for every workflow aggregate for which
   * the application did not contribute a runner. There is one instance for the whole
   * application. It also answers whether the transaction it runs was marked
   * rollback-only, and that answer belongs to the instance which opened it.
   *
   * @param transactionManager The application's transaction manager, looked up lazily. An
   *          application without transactional persistence still boots. The startup check
   *          of the process services names every way to give it one
   * @return The platform's runner
   */
  @Bean
  public SpringTransactionRunner vanillaBpPlatformTransactionRunner(
      final ObjectProvider<PlatformTransactionManager> transactionManager) {

    return new SpringTransactionRunner(transactionManager);

  }

  /**
   * Finds the transaction runner per workflow aggregate. It is injected into the
   * process-service beans, and {@link #vanillaBpProcessServiceStartupValidation} calls it
   * at startup.
   *
   * @param applicationContext Used to look up runner/aware beans and repositories
   * @param platformTransactionRunner The platform's runner, the last step which can give
   *          one. After it, the resolver has nothing left to offer, and the core turns
   *          that into a guiding startup failure
   * @return The resolver
   */
  @Bean
  public SpringTransactionRunnerResolver vanillaBpTransactionRunnerResolver(
      final ApplicationContext applicationContext,
      final SpringTransactionRunner platformTransactionRunner) {

    return new SpringTransactionRunnerResolver(applicationContext, platformTransactionRunner);

  }

  /**
   * The pre-commit hook for adapters. A BPMS adapter hands its phase-one check here. It
   * runs right before the transaction of the workflow aggregate commits. So the window
   * between the check and the phase-two dispatch stays as small as the platform allows.
   * The runner of the aggregate is looked up first. That way, a unit of work which the
   * application owns is the one the check hooks into.
   *
   * @param transactionRunnerResolver Resolves the runner of a workflow aggregate
   * @return The pre-commit hook
   */
  @Bean
  @ConditionalOnMissingBean(PreCommitRegistrar.class)
  public PreCommitRegistrar vanillaBpPreCommitRegistrar(
      final SpringTransactionRunnerResolver transactionRunnerResolver) {

    return new SpringPreCommitRegistrar(transactionRunnerResolver);

  }

  /**
   * Finds the phase-two outbox per workflow aggregate (mixed persistence, dedicated
   * outboxes). It is injected into the process-service beans, and
   * {@link #vanillaBpProcessServiceStartupValidation} calls it at startup.
   *
   * @param applicationContext Used to look up outbox/aware beans and repositories
   * @return The resolver
   */
  @Bean
  public SpringPhaseTwoOutboxResolver vanillaBpPhaseTwoOutboxResolver(
      final ApplicationContext applicationContext) {

    return new SpringPhaseTwoOutboxResolver(applicationContext);

  }

  /**
   * Finds the log of processed task deliveries per workflow aggregate (mixed persistence,
   * own stores). It is injected into the process-service beans, and
   * {@link #vanillaBpProcessServiceStartupValidation} calls it at startup.
   *
   * @param applicationContext Used to look up log/aware beans and repositories
   * @return The resolver
   */
  @Bean
  public SpringTaskDeliveryLogResolver vanillaBpTaskDeliveryLogResolver(
      final ApplicationContext applicationContext) {

    return new SpringTaskDeliveryLogResolver(applicationContext);

  }

  /**
   * Startup validation of the process services. It runs once all singletons exist, so no
   * persistence infrastructure is created while a bean is being built. The phase-two
   * outbox of every process service is looked up, per aggregate (mixed persistence,
   * dedicated outboxes). A missing outbox fails the startup with a guiding message. It
   * does not wait until the first workflow start. The log of processed task deliveries is
   * looked up in the same pass. A BPMS which repeats deliveries without a log to remember
   * them is reported at startup, not at the first redelivery.
   *
   * @param beanFactory Used to iterate all process-service beans
   * @return The startup validation hook
   */
  @Bean
  public SmartInitializingSingleton vanillaBpProcessServiceStartupValidation(
      final ConfigurableListableBeanFactory beanFactory) {

    return () -> beanFactory
        .getBeanProvider(ProcessService.class)
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(processServiceBean -> (ProcessServiceSpringBean<?>) processServiceBean)
        // once per DECLARED BPMN process id, not once per bean. A secondary or
        // declared-only id has prioritized adapters, an outbox and persisted leftovers of
        // its own. The point of asking is a message which names the id it is about
        .flatMap(processService -> processService.getProcessServicesOfDeclaredIds().stream())
        .forEach(processService -> {
          // first of all: an adapter which cannot serve an operation that every adapter
          // has to serve. Nothing later would report that gap, except a workflow which
          // stands still
          processService.validateAdapterOperationsAtStartup();
          processService.validatePhaseTwoOutboxAtStartup();
          // after the outbox: an application without a store hears about the store
          // first, because that is the more specific gap
          processService.validateTransactionRunnerAtStartup();
          processService.validateTaskDeliveryLogAtStartup();
          // last: it asks the stores which the two checks above found. So an application
          // which needs neither store is not made to create one just to be asked about it
          processService.validatePersistedAdapterIdsAtStartup();
        });

  }

}
