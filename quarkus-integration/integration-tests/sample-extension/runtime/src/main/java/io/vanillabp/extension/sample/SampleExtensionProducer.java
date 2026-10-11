package io.vanillabp.extension.sample;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * How the sample extension joins a Quarkus application: one producer and one
 * registration.
 * <p>
 * The contract is registered while the application starts. It does not matter whether the
 * workflow services were scanned by then. A contract which arrives later is applied to the
 * classes registered so far.
 */
@ApplicationScoped
public class SampleExtensionProducer {

  /**
   * @param properties VanillaBP's configuration. This extension's own section lives there
   * @return The factory building one service per workflow aggregate
   */
  @Produces
  @Singleton
  @Unremovable
  public AggregateServiceFactory<SampleNoteService> sampleNoteServiceFactory(
      final MigrationAdapterProperties properties) {

    return new SampleNoteServiceFactory(properties);

  }

  /**
   * Registers what {@link SampleNote} means.
   *
   * @param event The application starting
   * @param handlers The handler methods of the extensions
   */
  void registerHandlerContract(
      @Observes final StartupEvent event,
      final ExtensionHandlers handlers) {

    handlers.register(SampleNoteContract.build());

  }

}
