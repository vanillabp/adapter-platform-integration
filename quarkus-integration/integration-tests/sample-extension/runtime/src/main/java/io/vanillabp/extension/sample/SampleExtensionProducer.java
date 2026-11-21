package io.vanillabp.extension.sample;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;
import io.vanillabp.integration.extension.spi.settings.VanillaBpSettings;
import io.vanillabp.integration.spi.IdentifierScoping;
import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;
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
   * @param claims What the core knows about the workflow services. The extension takes the
   *          interface of the integration SPI, never <code>WorkflowTaskWiring</code>
   * @param scoping How a BPMS spells the identifiers. The extension takes the interface of
   *          the integration SPI, never <code>NameClashAvoidanceSupport</code>
   * @return The factory building one service per workflow aggregate
   */
  @Produces
  @Singleton
  @Unremovable
  public AggregateServiceFactory<SampleNoteService> sampleNoteServiceFactory(
      final VanillaBpSettings properties,
      final WorkflowTaskClaims claims,
      final IdentifierScoping scoping) {

    return new SampleNoteServiceFactory(properties, claims, scoping);

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
