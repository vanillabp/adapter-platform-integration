package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;
import jakarta.enterprise.inject.Instance;

/**
 * The Quarkus implementation of the core's {@link TaskDeliveryLogResolver}. It resolves the
 * {@link TaskDeliveryLog} for a workflow aggregate. So a delivery record is always written
 * in the aggregate's own transaction. It works like {@link QuarkusPhaseTwoOutboxResolver}.
 * The most specific {@link TaskDeliveryLogAware} bean of the application wins. Otherwise
 * the platform default wins which matches the technology that manages the aggregate
 * ({@link QuarkusPersistenceTechnology}). A single default which clearly does not match
 * ends the boot. It does not write records next to the aggregate.
 * <p>
 * Which default serves which technology, and whether it can be used at all, is asked
 * through {@link PlatformDefaultStore}. The implementations are not named. The MongoDB ones
 * exist only where the MongoDB client extension does. And a native image resolves every
 * referenced method while it is built.
 * <p>
 * The outbox resolver next to this one walks the same three steps. The two stay apart on
 * purpose. They look up different bean types, they answer with different stores, and each
 * message names the properties of its own store. A shared helper for the steps would need
 * all of that as input. And every reader of either resolver would have to open it to see
 * what happens.
 */
public class QuarkusTaskDeliveryLogResolver implements TaskDeliveryLogResolver {

  private final Instance<TaskDeliveryLogAware<?>> taskDeliveryLogAwares;

  private final Instance<TaskDeliveryLog> taskDeliveryLogs;

  private final QuarkusPersistenceTechnology persistenceTechnology;

  private final boolean jdbcLogEnabled;

  private final boolean mongoLogEnabled;

  /**
   * {@link TaskDeliveryLogResolverProducer} creates it, once per application. The two
   * switches arrive as booleans and not as a configuration object. So this class can be
   * tested without a Quarkus configuration (see {@code QuarkusStoreAttributionTest}).
   *
   * @param taskDeliveryLogAwares What the application says about its aggregates, if anything
   * @param taskDeliveryLogs Every delivery log bean of the application, the platform defaults
   *          included
   * @param persistenceTechnology Where an aggregate's technology is read from
   * @param jdbcLogEnabled Whether <code>vanillabp.outbox.jdbc.enabled</code> was left on. The
   *          log shares the switches of the outbox
   * @param mongoLogEnabled Whether <code>vanillabp.outbox.mongo.enabled</code> was left on
   */
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

    // 2. and 3.: choose by the technology which manages the aggregate
    final var logs = taskDeliveryLogs
        .stream()
        .filter(this::isActive)
        .toList();
    if (logs.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. Exactly one log: use it. But not if it is the platform default of the other
    // technology, because that would record the delivery outside the aggregate's
    // transaction.
    if (logs.size() == 1) {
      final var deliveryLog = logs.getFirst();
      if (mismatches(deliveryLog, technology)) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs));
      }
      return deliveryLog;
    }

    // 3. Several logs: the platform default of the aggregate's technology.
    return logs
        .stream()
        .filter(deliveryLog -> matches(deliveryLog, technology))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, logs)));

  }

  /**
   * Whether a log is the platform default which serves the given technology. A log of the
   * application matches nothing. Only its own {@link TaskDeliveryLogAware} bean says which
   * aggregates it serves.
   */
  private static boolean matches(
      final TaskDeliveryLog deliveryLog,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (deliveryLog instanceof final PlatformDefaultStore store) && (store.technology() == technology);

  }

  /**
   * Whether a log is the platform default of the OTHER technology. This is the one case in
   * which a single log bean must not be used.
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
   * Unwraps the CDI client proxy of a store. The proxy overrides every method of the bean
   * class, also the SPI's default which does nothing. So reflection on the proxy would
   * report a release which does not exist.
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
   * Whether the given log is active. The configuration may switch the platform defaults off,
   * or they cannot be used (no datasource, no MongoDB client). Such a default must not be
   * chosen.
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
   * Whether the default which serves a technology was left switched on. The log shares the
   * switches of the outbox (<code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code>).
   */
  private boolean isEnabled(
      final QuarkusPersistenceTechnology.Technology technology) {

    return technology == QuarkusPersistenceTechnology.Technology.MONGO
        ? mongoLogEnabled
        : jdbcLogEnabled;

  }

}
