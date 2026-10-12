package io.vanillabp.integration.processservice;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;

/**
 * Spring Boot implementation of the core's {@link TaskDeliveryLogResolver}. It finds the
 * {@link TaskDeliveryLog} for a workflow aggregate. So delivery records always ride the
 * aggregate's own transaction, also in applications with mixed persistence. The order is
 * the same one {@link SpringPhaseTwoOutboxResolver} uses for outbox entries:
 * <ol>
 * <li>the most specific {@link TaskDeliveryLogAware} bean which covers the aggregate
 * class,</li>
 * <li>the single {@link TaskDeliveryLog} bean if exactly one exists. The exception is a
 * platform default which does NOT match the persistence technology detected for the
 * aggregate. That mismatch would break the atomicity of record and aggregate change, so
 * it fails with a guiding message instead,</li>
 * <li>with several log beans: the platform-default bean which matches the persistence
 * technology of the aggregate (managed by JPA → the JDBC default, managed by MongoDB →
 * the MongoDB default).</li>
 * </ol>
 * <p>
 * The outbox resolver next to this one walks the same three steps. The two stay apart on
 * purpose: they look up different bean types, an outbox has two JPA default bean names
 * while this one has a single default per technology, and each message names the
 * properties of its own store. A shared step-walker would have to be handed all of that,
 * and every reader of either resolver would have to open it to see what happens.
 */
public class SpringTaskDeliveryLogResolver implements TaskDeliveryLogResolver {

  private final ApplicationContext applicationContext;

  private final SpringPersistenceTechnology persistenceTechnology;

  /**
   * Built once per application by the autoconfiguration. The log beans are looked up on
   * every question and not kept here. So a store which is created late is found too.
   *
   * @param applicationContext The context the delivery-log beans and the aggregate's
   *     repository are looked up in
   */
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

    // 2. exactly one log bean: use it, unless it is a platform default which clearly does
    // not match the aggregate's persistence. A record which does not commit with the
    // aggregate is worse than no deduplication, so this fails with a guiding message
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

    // 3. several log beans: choose by the persistence technology of the aggregate, and
    // take THE platform-default bean of that technology
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
   * Unwraps a proxied store. An application may put <code>&#64;Transactional</code> on its
   * own {@link TaskDeliveryLog}. The proxy Spring creates for it overrides every method of
   * the target, including the SPI's default which does nothing. So reflection on the proxy
   * would report a release which does not exist.
   */
  @Override
  public Class<?> storeClassOf(
      final TaskDeliveryLog deliveryLog) {

    return AopProxyUtils.ultimateTargetClass(deliveryLog);

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
