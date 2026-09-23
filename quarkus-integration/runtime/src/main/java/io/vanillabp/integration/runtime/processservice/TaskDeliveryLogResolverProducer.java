package io.vanillabp.integration.runtime.processservice;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.arc.Unremovable;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Produces the {@link TaskDeliveryLogResolver} of this platform as a CDI bean, the one
 * place a Quarkus application builds it: the generated process-service beans (see
 * {@link ProcessServiceBaseCdiBean}) resolve the log of their aggregate through it, and an
 * extension reading what a workflow is waiting for asks it which store holds the records
 * of that aggregate.
 * <p>
 * An extension must not answer that question itself, for the reason
 * {@link PhaseTwoOutboxResolverProducer} spells out for the outbox: which store may serve
 * an aggregate follows the persistence VanillaBP resolved for it and whether a platform
 * default is switched on and usable at all, and both are facts of this module
 * ({@link PlatformDefaultStore} is part of no SPI). An extension guessing along would read
 * a store the aggregate never wrote to. The Spring Boot integration offers its resolver as
 * a bean for the same reason, so what an extension can inject there it can inject here.
 * <p>
 * Reading is all an extension does with the answer. The records are written by the core,
 * in the transaction which saves the workflow aggregate, and a second writer would put
 * work into the log which never ran.
 * <p>
 * The configuration is READ, not injected, for the reason
 * {@link WorkflowAdapterCacheProducer} names: an injected config mapping is validated
 * before the adapter extensions registered their run-time overlays, and every
 * adapter-specific key would then end the startup as unknown.
 */
@ApplicationScoped
public class TaskDeliveryLogResolverProducer {

  /**
   * Built by the CDI container, which the platform's build step told about this class. What
   * the producer method below needs arrives through the injected fields.
   */
  public TaskDeliveryLogResolverProducer() {
  }

  /**
   * Application-provided attributions of aggregates to delivery logs (required in
   * mixed-persistence setups, optional otherwise).
   */
  @Inject
  @Any
  Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  /**
   * The logs available at runtime: the platform defaults of both technologies plus
   * whatever the application contributed. Unsatisfied where no store is available at all
   * (no datasource, no MongoDB client) - the resolver answers <code>null</code> then, and
   * the startup validation says what a BPMS repeating a delivery costs without one.
   */
  @Inject
  @Any
  Instance<TaskDeliveryLog> taskDeliveryLogs;

  /**
   * The persistences of the application, which is what an aggregate's store is read off.
   * Injected as {@link Instance} because a class annotated by
   * {@link io.vanillabp.spi.service.WorkflowService} may implement
   * {@link AggregatePersistenceAware} itself.
   */
  @Inject
  @Any
  Instance<AggregatePersistenceAware<?>> aggregatePersistences;

  /**
   * Builds the resolver of this application: the beans injected above, plus the two switches
   * the log shares with the outbox, read out of the configuration. What ends up in such a log
   * is decided in decision 54 in the repository's DECISIONS.md.
   *
   * @return The resolver, injectable by extensions and used by the process services
   */
  @Produces
  @Singleton
  @Unremovable
  public TaskDeliveryLogResolver taskDeliveryLogResolver() {

    final var outboxProperties = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(QuarkusMigrationAdapterProperties.class)
        .outbox();
    return new QuarkusTaskDeliveryLogResolver(
        taskDeliveryLogAwares, taskDeliveryLogs, new QuarkusPersistenceTechnology(aggregatePersistences), outboxProperties
            .jdbc()
            .enabled(), outboxProperties
                .mongo()
                .enabled());

  }

}
