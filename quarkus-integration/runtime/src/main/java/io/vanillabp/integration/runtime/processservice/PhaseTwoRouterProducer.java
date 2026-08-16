package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.PhaseTwoOperationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Produces the core-owned {@link PhaseTwoRouter} as CDI bean: the generated
 * process-service beans (see {@link ProcessServiceBaseCdiBean}) register themselves
 * with the router at bean creation, and the phase-two outbox dispatcher routes
 * committed outbox entries through it.
 * <p>
 * The router gets a {@link QuarkusTransactionRunner}: dispatching happens on the
 * outbox dispatcher's own thread and calls the application's aggregate persistence,
 * which needs a transaction and an active CDI request context - neither of which
 * exists on that thread. Providing it here covers VanillaBP's two outboxes and any
 * outbox an application contributes.
 */
@ApplicationScoped
public class PhaseTwoRouterProducer {

  @Produces
  @Singleton
  public PhaseTwoRouter phaseTwoRouter(
      final TransactionSynchronizationRegistry transactionRegistry) {

    return new PhaseTwoRouter(new QuarkusTransactionRunner(transactionRegistry));

  }

  /**
   * The router's registry of phase-two operations - injectable so an extension can
   * register operations of its own (VanillaBP's core operations are registered by
   * the router itself).
   *
   * @param phaseTwoRouter The router owning the registry
   * @return The operation registry
   */
  @Produces
  @Singleton
  public PhaseTwoOperationRegistry phaseTwoOperationRegistry(
      final PhaseTwoRouter phaseTwoRouter) {

    return phaseTwoRouter.getOperations();

  }

}
