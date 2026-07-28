package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the core-owned {@link PhaseTwoRouter} as CDI bean: the generated
 * process-service beans (see {@link ProcessServiceBaseCdiBean}) register themselves
 * with the router at bean creation, and the phase-two outbox dispatcher routes
 * committed outbox entries through it.
 */
@ApplicationScoped
public class PhaseTwoRouterProducer {

  @Produces
  @Singleton
  public PhaseTwoRouter phaseTwoRouter() {

    return new PhaseTwoRouter();

  }

}
