package io.vanillabp.integration.processservice;

import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
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
      final ObjectProvider<AdapterConfigurationBase> adapterConfigurations) {

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

    final var workflowModuleIds = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    properties.validateProperties(adaptersLoaded, workflowModuleIds);
    properties.validateEnvironmentVariableUsage(rawPropertyNames(environment));

    return properties;

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
