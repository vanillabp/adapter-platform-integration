package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import jakarta.enterprise.inject.Instance;

/**
 * Quarkus implementation of the core's {@link TaskDeliveryLogResolver}: resolves the
 * {@link TaskDeliveryLog} used for a workflow aggregate so a delivery record always rides
 * the aggregate's own transaction. It mirrors {@link QuarkusPhaseTwoOutboxResolver}: the
 * most specific {@link TaskDeliveryLogAware} bean of the application wins, otherwise the
 * platform default matching the technology which manages the aggregate
 * ({@link QuarkusPersistenceTechnology}), and a single default clearly not matching it
 * ends the boot rather than writing records next to the aggregate.
 * <p>
 * Which default serves which technology, and whether it is usable at all, is asked through
 * {@link PlatformDefaultStore} rather than by naming the implementations: the MongoDB ones
 * exist only where the MongoDB client extension does, and a native image resolves every
 * referenced method while it is built.
 */
public class QuarkusTaskDeliveryLogResolver implements TaskDeliveryLogResolver {

  private final Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  private final Instance<TaskDeliveryLog> taskDeliveryLogs;

  private final QuarkusPersistenceTechnology persistenceTechnology;

  private final boolean jdbcLogEnabled;

  private final boolean mongoLogEnabled;

  public QuarkusTaskDeliveryLogResolver(
      final Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares,
      final Instance<TaskDeliveryLog> taskDeliveryLogs,
      final QuarkusPersistenceTechnology persistenceTechnology,
      final boolean jdbcLogEnabled,
      final boolean mongoLogEnabled) {

    this.taskDeliveryLogAwares = taskDeliveryLogAwares;
    this.taskDeliveryLogs = taskDeliveryLogs;
    this.persistenceTechnology = persistenceTechnology;
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

    // 2./3. attribute by the technology managing the aggregate
    final var logs = taskDeliveryLogs
        .stream()
        .filter(this::isActive)
        .toList();
    if (logs.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. exactly one log: use it - unless it is the platform default of the other
    // technology, which would record the delivery outside the aggregate's transaction
    if (logs.size() == 1) {
      final var deliveryLog = logs.getFirst();
      if (mismatches(deliveryLog, technology)) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs));
      }
      return deliveryLog;
    }

    // 3. several logs: the platform default of the aggregate's technology
    return logs
        .stream()
        .filter(deliveryLog -> matches(deliveryLog, technology))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs)));

  }

  /**
   * Whether a log is the platform default serving the given technology. A log of the
   * application matches nothing: only its own {@link TaskDeliveryLogAware} bean says
   * which aggregates it serves.
   */
  private static boolean matches(
      final TaskDeliveryLog deliveryLog,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (deliveryLog instanceof final PlatformDefaultStore store) && (store.technology() == technology);

  }

  /**
   * Whether a log is the platform default of the OTHER technology - the one case in which
   * a single log bean must not be used.
   */
  private static boolean mismatches(
      final TaskDeliveryLog deliveryLog,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (technology != QuarkusPersistenceTechnology.Technology.UNKNOWN) && (deliveryLog instanceof final PlatformDefaultStore store) && (store
        .technology() != technology);

  }

  private static String buildAttributionErrorMessage(
      final Class<?> workflowAggregateClass,
      final QuarkusPersistenceTechnology.Technology technology,
      final List<TaskDeliveryLog> logs) {

    return """
        The TaskDeliveryLog beans %s cannot be attributed to workflow aggregate '%s' (persistence \
        technology detected: %s)! A delivery record must be enlisted in the transaction persisting \
        the aggregate. To solve this either
        - provide a bean implementing io.vanillabp.integration.spi.TaskDeliveryLogAware for this \
        aggregate (returning the log matching its persistence), or
        - enable the platform default matching the aggregate's persistence (add the corresponding \
        extension; check 'vanillabp.outbox.jdbc.enabled' / 'vanillabp.outbox.mongo.enabled')."""
        .formatted(
            logs
                .stream()
                .map(deliveryLog -> deliveryLog.getClass().getName())
                .toList(),
            workflowAggregateClass.getName(),
            technology);

  }

  /**
   * Unwraps the CDI client proxy of a store: the proxy overrides every method of the bean
   * class, the SPI's default doing nothing included, so reflecting on it would report a
   * release which does not exist.
   */
  @Override
  public Class<?> storeClassOf(
      final TaskDeliveryLog deliveryLog) {

    return io.quarkus.arc.ClientProxy
        .unwrap(deliveryLog)
        .getClass();

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

    if (deliveryLog instanceof final PlatformDefaultStore store) {
      return isEnabled(store.technology()) && store.isAvailable();
    }
    return true;

  }

  /**
   * Whether the default serving a technology was left switched on - the log shares the
   * outbox' switches (<code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code>).
   */
  private boolean isEnabled(
      final QuarkusPersistenceTechnology.Technology technology) {

    return technology == QuarkusPersistenceTechnology.Technology.MONGO
        ? mongoLogEnabled
        : jdbcLogEnabled;

  }

}
