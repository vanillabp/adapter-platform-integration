package io.vanillabp.extension.sample;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;

/**
 * How the sample extension joins a Spring Boot application: two beans and one
 * registration.
 * <p>
 * The ordering uses the class NAME. So this module needs no dependency on the platform
 * integration, and that is the point of the module (see its POM).
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
public class SampleExtensionConfiguration implements InitializingBean {

  private final ExtensionHandlers handlers;

  public SampleExtensionConfiguration(
      final ExtensionHandlers handlers) {

    this.handlers = handlers;

  }

  /**
   * Registers what {@link SampleNote} means. It does not matter whether the workflow
   * services were scanned already. A contract registered later is applied to the classes
   * registered so far.
   */
  @Override
  public void afterPropertiesSet() {

    handlers.register(SampleNoteContract.build());

  }

  /**
   * @param properties The configuration of VanillaBP, which holds the section of this
   *          extension
   * @return The factory building one service per workflow aggregate
   */
  @Bean
  public AggregateServiceFactory<SampleNoteService> sampleNoteServiceFactory(
      final MigrationAdapterProperties properties) {

    return new SampleNoteServiceFactory(properties);

  }

}
