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
 * Produces the {@link TaskDeliveryLogResolver} of this platform as a CDI bean. This is the
 * one place where a Quarkus application builds it. The generated process-service beans (see
 * {@link ProcessServiceBaseCdiBean}) resolve the log of their aggregate through it. An
 * extension which reads what a workflow is waiting for asks it which store holds the
 * records of that aggregate.
 * <p>
 * An extension must not answer that question itself. {@link PhaseTwoOutboxResolverProducer}
 * gives the reason for the outbox, and it holds here too. Which store may serve an aggregate
 * follows from the persistence which VanillaBP resolved for it. It also follows from
 * whether a platform default is switched on and can be used at all. Only this module knows
 * both facts ({@link PlatformDefaultStore} is part of no SPI). An extension which guesses
 * would read a store which the aggregate never wrote to. The Spring Boot integration offers
 * its resolver as a bean for the same reason. So what an extension can inject there, it can
 * inject here.
 * <p>
 * An extension only reads with the answer. The core writes the records, in the transaction
 * which saves the workflow aggregate. A second writer would put work into the log which
 * never ran.
 * <p>
 * The configuration is READ, not injected. {@link WorkflowAdapterCacheProducer} names the
 * reason: an injected config mapping is checked before the adapter extensions registered
 * their run-time overlays. Then every adapter-specific key would end the startup as
 * unknown.
 */
@ApplicationScoped
public class TaskDeliveryLogResolverProducer {

  /**
   * The CDI container creates it, because the platform's build step told it about this class.
   * What the producer method below needs arrives through the injected fields.
   */
  public TaskDeliveryLogResolverProducer() {
  }

  /**
   * What the application says about which delivery log serves which aggregate. It is
   * required where the application mixes persistences, and optional otherwise.
   */
  @Inject
  @Any
  Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  /**
   * The logs which are there at runtime: the platform defaults of both technologies plus
   * whatever the application added. It is unsatisfied where there is no store at all (no
   * datasource, no MongoDB client). Then the resolver answers <code>null</code>. The startup
   * check says what a BPMS which repeats a delivery costs without a log.
   */
  @Inject
  @Any
  Instance<TaskDeliveryLog> taskDeliveryLogs;

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
   * switches which the log shares with the outbox, read from the configuration. What ends up
   * in such a log is decided in decision 54 in the repository's DECISIONS.md.
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
