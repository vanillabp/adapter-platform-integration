package io.vanillabp.integration.processservice;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;
import io.vanillabp.integration.config.SpringBootMigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterTransformer;
import io.vanillabp.integration.utils.SpringDataUtil;
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
@EnableConfigurationProperties(SpringBootMigrationAdapterProperties.class)
@Import(ProcessServiceBeanRegistrar.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  static final String BEANNAME_MIGRATIONADAPERPROPERTIES = "VanillaBpMigrationAdapterProperties";

  /**
   * Maps and validates VanillaBP properties (specific to Spring Boot) to
   * {@link MigrationAdapterProperties} bean. It is used by common adapter
   * implementation of module "migration-adapter".
   *
   * @param properties The Spring Boot specific properties
   * @param allWorkflowModules All workflow modules found in classpath
   * @param adapterConfigurations Configuration beans of adapters found in classpath
   * @return The properties bean not specific to Spring Boot
   */
  @Bean(BEANNAME_MIGRATIONADAPERPROPERTIES)
  public static MigrationAdapterProperties migrationAdapterProperties(
      final SpringBootMigrationAdapterProperties properties,
      final WorkflowModules allWorkflowModules,
      final List<AdapterConfigurationBase> adapterConfigurations) {

    final var adaptersLoaded = Optional
        .ofNullable(adapterConfigurations)
        .orElse(List.of())
        .stream()
        .map(AdapterConfigurationBase::getAdapterType)
        .toList();

    final var workflowModuleIds = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    return SpringBootMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .adaptersFound(adaptersLoaded)
        .workflowModulesFound(workflowModuleIds)
        .build()
        .getAndValidatePropertiesConfigured();

  }

  /**
   * The bean receiving phase-two calls dispatched by a
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementation and
   * routing them to the {@link io.vanillabp.spi.process.ProcessService} bean
   * responsible for the workflow module and BPMN process given.
   *
   * @param processServices Provider of all process service beans registered
   * @param springDataUtil Provider of the persistence utility used to determine aggregate-ID types
   * @return The phase-two bean
   */
  @Bean
  @ConditionalOnMissingBean(MigratableProcessServicePhaseTwo.class)
  @SuppressWarnings("rawtypes")
  public MigratableProcessServicePhaseTwoSpringBean migratableProcessServicePhaseTwo(
      final ObjectProvider<io.vanillabp.spi.process.ProcessService> processServices,
      final ObjectProvider<SpringDataUtil> springDataUtil) {

    return new MigratableProcessServicePhaseTwoSpringBean(processServices, springDataUtil);

  }

}
