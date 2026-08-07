package io.vanillabp.integration.processservice;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.ClasspathFacts;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP adapters. The
 * {@link io.vanillabp.spi.process.ProcessService} beans are registered by the
 * imported {@link ProcessServiceBeanRegistrar}.
 */
@Slf4j
@AutoConfiguration(after = WorkflowModuleAutoConfiguration.class)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
@Import(ProcessServiceBeanRegistrar.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  static final String BEANNAME_MIGRATIONADAPERPROPERTIES = "VanillaBpMigrationAdapterProperties";

  /**
   * Converts the values of <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>
   * case-insensitively into the core enum. Registered as a
   * {@link ConfigurationPropertiesBinding} converter so an invalid value fails the
   * binding with a message naming the allowed values (Spring's bind failure adds the
   * offending property key and value).
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
   * Validates the directly bound {@link VanillaBpConfigurationProperties} (facts
   * from the classpath are passed into the core validation) and provides them as
   * the platform-neutral {@link MigrationAdapterProperties} bean used by the common
   * adapter implementation of module "migration-adapter".
   *
   * @param properties The bound core properties
   * @param environment The Spring environment (used to detect environment variables
   *          not taken over by the binding)
   * @param allWorkflowModules All workflow modules found in classpath
   * @param adapterConfigurations Configuration beans of adapters found in classpath
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

    // ObjectProvider (not a required List): without any adapter on the classpath the
    // bean creation still runs and the guiding message
    // "No adapters found in classpath!" is actually reachable
    final var adaptersLoaded = adapterConfigurations
        .stream()
        .map(AdapterConfigurationBase::getAdapterType)
        .toList();
    if (adaptersLoaded.isEmpty()) {
      throw new IllegalStateException(
          "No adapters found in classpath! Add dependencies providing VanillaBP adapters.");
    }

    // convention over configuration (story 34): the classpath facts are what the
    // conventions are derived from - which workflow module descriptor comes from
    // the application's MAIN artifact decides the conventional resources location
    final var mainArtifactRootPrefix = mainArtifactRootPrefix(applicationContext);
    final var workflowModules = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(workflowModule -> new ClasspathFacts.WorkflowModuleInfo(
            workflowModule.getId(), (mainArtifactRootPrefix != null) && mainArtifactRootPrefix
                .equals(workflowModule.getSourceUri())))
        .toList();

    properties.validateProperties(
        new ClasspathFacts(adaptersLoaded, workflowModules),
        null);
    properties.validateEnvironmentVariableUsage(rawPropertyNames(environment));

    return properties;

  }

  /**
   * The core-owned sync model (story 28): turns a workflow aggregate into the
   * values shared with the BPMS, honoring
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} and the adapter's default. One
   * instance per application - BPMS adapters receive it and decide what to do with
   * the values (push them as process variables, or write them as operator context
   * only).
   *
   * @return The sync support
   */
  @Bean
  @ConditionalOnMissingBean
  public static io.vanillabp.integration.adapter.spi.WorkflowAggregateSync vanillaBpWorkflowAggregateSync() {

    return new io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport();

  }

  /**
   * The classpath-root prefix of the application's MAIN artifact: the JAR or
   * directory the <code>&#64;SpringBootApplication</code> class was loaded from.
   * A workflow module descriptor found in that very root belongs to the
   * application itself (and not to a workflow module shipped as its own
   * artifact), which decides the conventional resources location.
   * <p>
   * Bean TYPES are inspected (never instances), so nothing is instantiated early.
   * An application without such a class (e.g. a plain
   * <code>&#64;Configuration</code> test context) yields <code>null</code> - every
   * workflow module is then treated as an own artifact, the conservative choice.
   *
   * @param applicationContext The application context
   * @return The classpath-root prefix or <code>null</code>
   */
  private static String mainArtifactRootPrefix(
      final ApplicationContext applicationContext) {

    return Arrays
        .stream(applicationContext.getBeanNamesForAnnotation(SpringBootApplication.class))
        .map(applicationContext::getType)
        .filter(java.util.Objects::nonNull)
        .map(ClassUtils::getUserClass)
        .map(WorkflowModuleAutoConfiguration::determineClasspathRootPrefix)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * Collects the raw names of all enumerable properties of the environment -
   * including the unconverted environment-variable names of the
   * <code>systemEnvironment</code> property source, which the core uses to detect
   * <code>VANILLABP_*</code> variables not taken over by the binding.
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
   * The core-owned router receiving phase-two calls dispatched by a
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementation and
   * routing them to the {@link io.vanillabp.spi.process.ProcessService} bean
   * responsible for the workflow module and BPMN process given. The process-service
   * beans register themselves (including the aggregate-ID converter) at
   * bean-creation time.
   *
   * @return The phase-two router
   */
  @Bean
  @ConditionalOnMissingBean(PhaseTwoRouter.class)
  public PhaseTwoRouter vanillaBpPhaseTwoRouter() {

    return new PhaseTwoRouter();

  }

  /**
   * The cache of workflow&rarr;adapter associations consulted by the BPMS election
   * for operations on existing workflows (complete/cancel task, user task, message
   * correlation): a bounded, expiring in-memory default. Cluster setups wanting
   * instances to share elections define their own bean implementing
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache} backed by their own
   * cache infrastructure - it replaces this default (entries are hints: a stale
   * entry costs an extra probe, never correctness).
   *
   * @return The default in-memory cache
   */
  @Bean
  @ConditionalOnMissingBean(io.vanillabp.integration.spi.WorkflowAdapterCache.class)
  public io.vanillabp.integration.spi.WorkflowAdapterCache vanillaBpWorkflowAdapterCache() {

    return new io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache();

  }

  /**
   * The core-owned name-clash-avoidance model (story 35): resolves the mode per
   * workflow module/workflow and adapter and composes the identifiers a BPMS sees.
   * One instance per application - BPMS adapters receive it and apply it to their
   * model and their commands.
   *
   * @param properties The VanillaBP configuration
   * @return The name-clash-avoidance support
   */
  @Bean
  @ConditionalOnMissingBean
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport vanillaBpNameClashAvoidanceSupport(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    return new io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService(properties);

  }

  /**
   * The core-owned registry of <code>&#64;WorkflowTask</code> handlers and
   * adapter-facing {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}:
   * the process-service beans register every workflow service class under all BPMN
   * process IDs it declares; adapters validate the wiring during
   * <code>wireBpmn</code> and dispatch task invocations at runtime. Handlers run via
   * the Spring transaction runner (a lazily resolved
   * {@link org.springframework.transaction.PlatformTransactionManager} - an
   * application without transactional persistence still boots and gets a guiding
   * message when the first task is processed).
   *
   * @param transactionManager The application's transaction manager, resolved lazily
   * @param aggregateSync The core's sync model - validated per registered
   *          workflow-aggregate class at startup (story 28b) and used to answer the
   *          shared values of an aggregate an adapter does not hold
   * @return The workflow-task registry
   */
  @Bean
  public WorkflowTaskRegistry vanillaBpWorkflowTaskRegistry(
      final org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManager,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync) {

    return new WorkflowTaskRegistry(new SpringTransactionRunner(transactionManager), aggregateSync);

  }

  /**
   * Resolves the phase-two outbox per workflow aggregate (mixed persistence,
   * dedicated outboxes) - injected into the process-service beans and invoked at
   * startup by {@link #vanillaBpProcessServiceStartupValidation}.
   *
   * @param applicationContext Used to look up outbox/aware beans and repositories
   * @return The resolver
   */
  @Bean
  public SpringPhaseTwoOutboxResolver vanillaBpPhaseTwoOutboxResolver(
      final org.springframework.context.ApplicationContext applicationContext) {

    return new SpringPhaseTwoOutboxResolver(applicationContext);

  }

  /**
   * Startup validation of the process services, run once all singletons exist (so
   * no persistence infrastructure is materialized mid-bean-construction): for every
   * process service whose first-priority adapter requires a two-phase commit the
   * phase-two outbox is resolved (per aggregate - mixed persistence, dedicated
   * outboxes) - a missing outbox fails the startup with a guiding message instead
   * of surfacing at the first workflow start.
   *
   * @param beanFactory Used to iterate all process-service beans
   * @return The startup validation hook
   */
  @Bean
  public SmartInitializingSingleton vanillaBpProcessServiceStartupValidation(
      final ConfigurableListableBeanFactory beanFactory) {

    return () -> beanFactory
        .getBeanProvider(io.vanillabp.spi.process.ProcessService.class)
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(ProcessServiceSpringBean.class::cast)
        .forEach(processService -> processService
            .getMigrationProcessService()
            .validatePhaseTwoOutboxAtStartup());

  }

}
