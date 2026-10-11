package io.vanillabp.integration.runtime.processservice;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the {@link PhaseTwoRouter}, which the core owns, as a CDI bean. The generated
 * process-service beans (see {@link ProcessServiceBaseCdiBean}) register themselves with the
 * router when they are created. The phase-two outbox dispatcher routes committed outbox
 * entries through it.
 * <p>
 * The router gets a {@link QuarkusTransactionRunner}. Dispatching happens on the outbox
 * dispatcher's own thread and calls the application's aggregate persistence. That needs a
 * transaction and an active CDI request context, and that thread has neither. Giving the
 * runner here covers VanillaBP's two outboxes and any outbox which an application adds.
 */
@ApplicationScoped
public class PhaseTwoRouterProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * The producer methods below take what they need as parameters. So the producer itself
   * holds nothing.
   */
  public PhaseTwoRouterProducer() {
  }

  /**
   * Builds the one router of the application. An outbox entry is handed to it after the
   * caller's transaction committed. It is the acting half of the split which decision 3 in
   * the repository's DECISIONS.md describes.
   *
   * @param platformTransactionRunner The transaction which a dispatch runs in. It is the
   *          platform's own runner, the bean of {@link TransactionRunnerProducer}
   * @param metrics Where dispatches are counted. It is unsatisfied where the application
   *          uses no Micrometer extension
   * @return The router
   */
  @Produces
  @Singleton
  public PhaseTwoRouter phaseTwoRouter(
      final QuarkusTransactionRunner platformTransactionRunner,
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    final var router = new PhaseTwoRouter(platformTransactionRunner);
    router.setMetrics(vanillaBpMetricsOf(metrics));
    return router;

  }

  /**
   * The metrics implementation to use. It is the Micrometer one where the application uses
   * the Micrometer extension, and
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * otherwise.
   *
   * @param metrics The injected metrics
   * @return What to record into, never <code>null</code>
   */
  public static io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics vanillaBpMetricsOf(
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    return ((metrics != null) && metrics.isResolvable())
        ? metrics.get()
        : io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  }

  /**
   * The router's registry of phase-two operations. It can be injected, so an extension can
   * register operations of its own. The router itself registers VanillaBP's core
   * operations.
   *
   * @param phaseTwoRouter The router owning the registry
   * @return The operation registry
   */
  @Produces
  @Singleton
  public PhaseOperationRegistry phaseTwoOperationRegistry(
      final PhaseTwoRouter phaseTwoRouter) {

    return phaseTwoRouter.getOperations();

  }

  /**
   * The election, offered to extensions. It tells which BPMS holds a given workflow right
   * now. An extension which used the first-priority adapter instead would talk to the wrong
   * BPMS for every workflow which a migration has already moved.
   *
   * @param phaseTwoRouter The router holding the process services of this application
   * @return The extension-facing election
   */
  @Produces
  @Singleton
  @io.quarkus.arc.Unremovable
  public io.vanillabp.integration.extension.spi.election.WorkflowElection workflowElection(
      final PhaseTwoRouter phaseTwoRouter) {

    return new io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection(phaseTwoRouter);

  }

}
