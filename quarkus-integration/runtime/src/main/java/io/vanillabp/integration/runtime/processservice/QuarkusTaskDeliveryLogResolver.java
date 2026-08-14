package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import jakarta.enterprise.inject.Instance;

/**
 * Quarkus implementation of the core's {@link TaskDeliveryLogResolver}: resolves the
 * {@link TaskDeliveryLog} used for a workflow aggregate so a delivery record always rides
 * the aggregate's own transaction. It mirrors {@link QuarkusPhaseTwoOutboxResolver} -
 * including its one difference to Spring Boot: Quarkus has no platform-side knowledge of
 * which persistence manages an aggregate (aggregate persistence is always provided by the
 * application, see {@link io.vanillabp.integration.spi.AggregatePersistenceAware}), so a
 * mixed-persistence application attributes aggregates to logs via
 * {@link TaskDeliveryLogAware} beans.
 */
public class QuarkusTaskDeliveryLogResolver implements TaskDeliveryLogResolver {

  private final Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  private final Instance<TaskDeliveryLog> taskDeliveryLogs;

  private final boolean jdbcLogEnabled;

  private final boolean mongoLogEnabled;

  public QuarkusTaskDeliveryLogResolver(
      final Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares,
      final Instance<TaskDeliveryLog> taskDeliveryLogs,
      final boolean jdbcLogEnabled,
      final boolean mongoLogEnabled) {

    this.taskDeliveryLogAwares = taskDeliveryLogAwares;
    this.taskDeliveryLogs = taskDeliveryLogs;
    this.jdbcLogEnabled = jdbcLogEnabled;
    this.mongoLogEnabled = mongoLogEnabled;

  }

  @Override
  public TaskDeliveryLog resolveFor(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific TaskDeliveryLogAware bean covering the aggregate class
    final List<TaskDeliveryLogAware<?>> awares = taskDeliveryLogAwares
        .stream()
        .<TaskDeliveryLogAware<?>>map(aware -> aware)
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

    // 2./3. the single active log bean - or a guiding error if ambiguous
    final var logs = taskDeliveryLogs
        .stream()
        .filter(this::isActive)
        .toList();
    if (logs.isEmpty()) {
      return null;
    }
    if (logs.size() > 1) {
      throw new IllegalStateException(
          """
              Several TaskDeliveryLog beans exist (%s), but none can be attributed to workflow \
              aggregate '%s'! A delivery record must be enlisted in the transaction persisting the \
              aggregate. To solve this either
              - provide a bean implementing io.vanillabp.integration.spi.TaskDeliveryLogAware for \
              this aggregate (returning the log matching its persistence), or
              - deactivate the unwanted default ('vanillabp.outbox.jdbc.enabled' / \
              'vanillabp.outbox.mongo.enabled')."""
              .formatted(
                  logs
                      .stream()
                      .map(deliveryLog -> deliveryLog.getClass().getName())
                      .toList(),
                  workflowAggregateClass.getName()));
    }
    return logs.getFirst();

  }

  @Override
  public String remediesDescription() {

    return """
        - add the 'quarkus-agroal' extension and configure a JDBC datasource (enables the JDBC default),
        - add the 'quarkus-mongodb-client' extension and configure the MongoDB connection incl. 'quarkus.mongodb.database' (enables the MongoDB default),""";

  }

  /**
   * Whether the given log is active: the platform defaults may be deactivated by
   * configuration or be unusable (no datasource, no MongoDB client) - such a default
   * must not be selected.
   *
   * @param deliveryLog The log bean
   * @return Whether the log may be selected
   */
  private boolean isActive(
      final TaskDeliveryLog deliveryLog) {

    if (deliveryLog instanceof JdbcTaskDeliveryLog jdbcLog) {
      return jdbcLogEnabled && jdbcLog.isAvailable();
    }
    if (deliveryLog instanceof MongoTaskDeliveryLog mongoLog) {
      return mongoLogEnabled && mongoLog.isAvailable();
    }
    return true;

  }

}
