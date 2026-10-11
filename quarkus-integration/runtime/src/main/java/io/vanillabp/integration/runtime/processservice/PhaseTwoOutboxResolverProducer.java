package io.vanillabp.integration.runtime.processservice;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Unremovable;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Produces the {@link PhaseTwoOutboxResolver} of this platform as a CDI bean. This is the
 * one place where a Quarkus application builds it. The generated process-service beans (see
 * {@link ProcessServiceBaseCdiBean}) resolve the outbox of their aggregate through it. An
 * extension which writes entries of its own into the aggregate's transaction asks it which
 * store that transaction reaches.
 * <p>
 * An extension must not answer that question itself. Whether a store may serve an aggregate
 * depends on the persistence which VanillaBP resolved for the aggregate. It also depends on
 * whether a platform default is switched on and can be used at all. Only this module knows
 * these facts ({@link PlatformDefaultStore} is not part of any SPI). An extension which
 * guesses would write entries next to the aggregate and not into its transaction. The
 * Spring Boot integration offers its resolver as a bean for the same reason.
 * <p>
 * The configuration is READ, not injected. {@link WorkflowAdapterCacheProducer} names the
 * reason: an injected config mapping is checked before the adapter extensions registered
 * their run-time overlays. Then every adapter-specific key would end the startup as
 * unknown.
 */
@ApplicationScoped
public class PhaseTwoOutboxResolverProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * What the producer method below needs arrives through the injected fields.
   */
  public PhaseTwoOutboxResolverProducer() {
  }

  /**
   * What the application says about which outbox serves which aggregate. It is required
   * where the application brought the persistence itself, and optional otherwise.
   */
  @Inject
  @Any
  Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  /**
   * The outboxes which are there at runtime: the platform defaults of both technologies plus
   * whatever the application added.
   */
  @Inject
  @Any
  Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  /**
   * The persistences of the application. They tell which store an aggregate uses. They are
   * injected as {@link Instance}, because a class annotated with
   * {@link io.vanillabp.spi.service.WorkflowService} may implement
   * {@link AggregatePersistenceAware} itself.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Builds the resolver of this application from the beans injected above and the two
   * switches of the platform defaults, read from the configuration. The switches are passed
   * in as plain booleans. So the resolver itself knows nothing about Quarkus configuration,
   * and a test can build it (see {@code QuarkusStoreAttributionTest}).
   *
   * @return The resolver, injectable by extensions and used by the process services
   */
  @Produces
  @Singleton
  @Unremovable
  public PhaseTwoOutboxResolver phaseTwoOutboxResolver() {

    final var outboxProperties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();
    return new QuarkusPhaseTwoOutboxResolver(
        phaseTwoOutboxAwares, phaseTwoOutboxes, new QuarkusPersistenceTechnology(aggregatePersistences), outboxProperties
            .jdbc()
            .enabled(), outboxProperties
                .mongo()
                .enabled());

  }

}
