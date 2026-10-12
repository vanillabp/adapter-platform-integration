package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;

/**
 * Resolves the {@link TaskDeliveryLog} which is used for a workflow aggregate. The platform
 * integrations implement it, and the core calls it AT STARTUP (see
 * {@link MigrationProcessService#validateTaskDeliveryLogAtStartup()}). It resolves the same
 * way as {@link PhaseTwoOutboxResolver}, because a delivery record has to use the aggregate's
 * own transaction just like an outbox entry:
 * <ol>
 * <li>the most specific {@link TaskDeliveryLogAware} bean which covers the aggregate class
 * (picked by {@link AwareSelection}),</li>
 * <li>the platform's default choice: the single available log bean, or, if there are
 * several, the platform's default log which matches the persistence technology that manages
 * the aggregate,</li>
 * <li><code>null</code> if no log is available at all. Then the core reports at startup that
 * deliveries are not deduplicated, and names the remedies.</li>
 * </ol>
 */
public interface TaskDeliveryLogResolver {

  /**
   * Resolves the delivery log for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The log or <code>null</code> if none is available
   * @throws IllegalStateException If several logs exist and none can be assigned to the
   *           aggregate. The guiding message names the beans found and the remedy (provide a
   *           {@link TaskDeliveryLogAware} bean)
   */
  TaskDeliveryLog resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Lines with remedies for this platform. They are added to the core's guiding message when
   * no log is available, but the delivering BPMS may repeat deliveries (for example which
   * starter or extension enables a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

  /**
   * The class of the given log AS THE APPLICATION WROTE IT. The core reflects on it to find
   * out whether a store implements
   * {@link TaskDeliveryLog#releaseRecordsOf(String, String, String, java.time.Instant)}, or
   * inherits the default which does nothing. A platform which hands out proxies (CDI client
   * proxies, Spring AOP) has to unwrap them here. A proxy overrides every method of its
   * target, the default implementation included. So reflecting on the proxy would report a
   * release which does not exist.
   *
   * @param deliveryLog The resolved log
   * @return The class in which the store is written
   */
  default Class<?> storeClassOf(
      final TaskDeliveryLog deliveryLog) {

    return deliveryLog.getClass();

  }

}
