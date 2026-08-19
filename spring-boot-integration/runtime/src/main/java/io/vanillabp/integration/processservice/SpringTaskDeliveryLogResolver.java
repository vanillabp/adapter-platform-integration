package io.vanillabp.integration.processservice;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;

/**
 * Spring Boot implementation of the core's {@link TaskDeliveryLogResolver}: resolves the
 * {@link TaskDeliveryLog} used for a workflow aggregate so delivery records always ride
 * the aggregate's own transaction (also in mixed-persistence applications). Resolution
 * order - the same as {@link SpringPhaseTwoOutboxResolver} applies to outbox entries:
 * <ol>
 * <li>the most specific {@link TaskDeliveryLogAware} bean covering the aggregate
 * class,</li>
 * <li>the single {@link TaskDeliveryLog} bean if exactly one exists - unless it is a
 * platform default NOT matching the aggregate's detectable persistence technology (that
 * mismatch would break the atomicity of record and aggregate change and fails with a
 * guiding message instead),</li>
 * <li>with several log beans: the platform-default bean matching the persistence
 * technology managing the aggregate (JPA-managed → the JDBC default, Mongo-managed → the
 * MongoDB default).</li>
 * </ol>
 */
public class SpringTaskDeliveryLogResolver implements TaskDeliveryLogResolver {

  private final ApplicationContext applicationContext;

  private final SpringPersistenceTechnology persistenceTechnology;

  public SpringTaskDeliveryLogResolver(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;
    this.persistenceTechnology = new SpringPersistenceTechnology(applicationContext);

  }

  @Override
  public TaskDeliveryLog resolveFor(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific TaskDeliveryLogAware bean covering the aggregate class
    final var awares = applicationContext
        .getBeanProvider(TaskDeliveryLogAware.class)
        .stream()
        .<TaskDeliveryLogAware<?>>map(aware -> (TaskDeliveryLogAware<?>) aware)
        .toList();
    final var mostSpecificAware = AwareSelection.mostSpecific(
        awares,
        TaskDeliveryLogAware::getAggregateClass,
        workflowAggregateClass);
    if (mostSpecificAware.isPresent()) {
      return mostSpecificAware
          .get()
          .getTaskDeliveryLog();
    }

    final Map<String, TaskDeliveryLog> logs = applicationContext.getBeansOfType(TaskDeliveryLog.class);
    if (logs.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. exactly one log bean: use it - unless it is a platform default clearly not
    // matching the aggregate's persistence (a record which does not commit with the
    // aggregate is worse than no deduplication, so this fails guiding)
    if (logs.size() == 1) {
      final var entry = logs
          .entrySet()
          .iterator()
          .next();
      final var mismatch = ((technology == SpringPersistenceTechnology.Technology.MONGO) && entry
          .getKey()
          .equals(
              JdbcTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME)) || ((technology == SpringPersistenceTechnology.Technology.JPA) && entry
                  .getKey()
                  .equals(MongoTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME));
      if (mismatch) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs.keySet()));
      }
      return entry.getValue();
    }

    // 3. several log beans: attribute by the persistence technology managing the
    // aggregate - to THE platform-default bean of that technology
    final var defaultBeanName = switch (technology) {
      case JPA -> JdbcTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME;
      case MONGO -> MongoTaskDeliveryLogAutoConfiguration.DEFAULT_DELIVERY_LOG_BEAN_NAME;
      case UNKNOWN -> null;
    };
    return Optional
        .ofNullable(defaultBeanName)
        .map(logs::get)
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs.keySet())));

  }

  /**
   * Unwraps a proxied store: an application may put <code>&#64;Transactional</code> on
   * its own {@link TaskDeliveryLog}, and the proxy Spring creates for it overrides every
   * method of the target - the SPI's default doing nothing included. Reflecting on the
   * proxy would therefore report a release which does not exist.
   */
  @Override
  public Class<?> storeClassOf(
      final TaskDeliveryLog deliveryLog) {

    return org.springframework.aop.framework.AopProxyUtils.ultimateTargetClass(deliveryLog);

  }

  @Override
  public String remediesDescription() {

    return """
        - add spring-boot-starter-data-jpa and configure a data source (enables the JDBC-based default),
        - add spring-boot-starter-data-mongodb and configure the MongoDB connection (enables the MongoDB default),""";

  }

  private String buildAttributionErrorMessage(
      final Class<?> workflowAggregateClass,
      final SpringPersistenceTechnology.Technology technology,
      final Set<String> deliveryLogBeanNames) {

    return """
        The TaskDeliveryLog beans %s cannot be attributed to workflow aggregate '%s' (persistence \
        technology detected: %s)! A delivery record must be enlisted in the transaction persisting \
        the aggregate. To solve this either
        - provide a bean implementing io.vanillabp.integration.spi.TaskDeliveryLogAware for this \
        aggregate (returning the log matching its persistence), or
        - enable the platform default matching the aggregate's persistence (add the corresponding \
        Spring Data starter; check 'vanillabp.outbox.jdbc.enabled' / 'vanillabp.outbox.mongo.enabled')."""
        .formatted(
            deliveryLogBeanNames,
            workflowAggregateClass.getName(),
            technology);

  }

}
