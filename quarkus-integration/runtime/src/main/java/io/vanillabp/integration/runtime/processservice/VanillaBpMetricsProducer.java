package io.vanillabp.integration.runtime.processservice;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Publishes what VanillaBP counts about its deliveries and its outbox as Micrometer meters.
 * Quarkus applies every {@code MeterBinder} bean to its registry. So producing the binder
 * is all it takes.
 * <p>
 * This class is registered as a bean ONLY if the application uses the Micrometer extension
 * (see {@code VanillaBpMetricsBuildStepProcessor}). It uses Micrometer types and must not be
 * loaded otherwise. The meters of the election cache work the same way.
 */
@ApplicationScoped
public class VanillaBpMetricsProducer {

  /**
   * The CDI container creates it, and only in an application which has the Micrometer
   * extension. The build step named in the class comment registers the bean.
   */
  public VanillaBpMetricsProducer() {
  }

  /**
   * Builds what VanillaBP counts into. Some gauges have to ask somebody for their value. How
   * long such a value is reused comes from the configuration and not from a constant,
   * because a scrape must not turn into load. See decision 18 in the repository's
   * DECISIONS.md.
   *
   * @param properties The VanillaBP configuration. It says how long the measurement of a
   *          gauge which has to ask somebody is reused
   * @return The meters of deliveries and outbox
   */
  @Produces
  @Singleton
  public MicrometerVanillaBpMetrics vanillaBpMetrics(
      final io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties properties) {

    return new MicrometerVanillaBpMetrics(
        properties
            .getMetrics()
            .resolvedGaugeCache());

  }

  /**
   * How late this runs: AFTER the outbox dispatchers, which also create their table at
   * startup ({@code VanillaBpDeploymentRunner.OUTBOX_DISPATCHER_STARTUP_PRIORITY}). A store
   * which is asked before its table exists cannot count. Then no gauge would be published
   * for it.
   */
  private static final int STARTUP_PRIORITY = jakarta.interceptor.Interceptor.Priority.APPLICATION + 800;

  /**
   * Publishes what each outbox store says about its backlog: how many entries wait
   * ({@link PhaseTwoOutbox#pendingCalls()}) and how long the oldest of them has been waiting
   * ({@link PhaseTwoOutbox#ageOfOldestPendingCall()}). Each gauge is published for the
   * stores which can answer its question. The two questions are asked one by one, because a
   * store may answer one of them and not the other. The gauges are tagged with the class
   * name of the store, because an application may run several stores.
   *
   * @param startup The startup event. The outbox stores must not be created while the
   *          beans are still being built
   * @param metrics The metrics to register the gauges with
   * @param outboxes All outbox stores of the application
   */
  void registerOutboxBacklogGauges(
      @Observes
      @jakarta.annotation.Priority(STARTUP_PRIORITY) final StartupEvent startup,
      final MicrometerVanillaBpMetrics metrics,
      @jakarta.enterprise.inject.Any final Instance<PhaseTwoOutbox> outboxes) {

    if (outboxes.isUnsatisfied()) {
      return;
    }
    outboxes
        .stream()
        .forEach(outbox -> {
          final var store = storeNameOf(outbox);
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

  /**
   * The name of an outbox store as the <code>store</code> tag shows it. CDI hands out client
   * proxies. Their class name has a suffix which nobody wants to read in a dashboard. So the
   * superclass is used where there is one.
   *
   * @param outbox The outbox store
   * @return The name to tag its gauge with
   */
  private static String storeNameOf(
      final PhaseTwoOutbox outbox) {

    final var candidate = outbox.getClass();
    return candidate
        .getSimpleName()
        .contains("_ClientProxy")
            ? candidate
                .getSuperclass()
                .getSimpleName()
            : candidate.getSimpleName();

  }

}
