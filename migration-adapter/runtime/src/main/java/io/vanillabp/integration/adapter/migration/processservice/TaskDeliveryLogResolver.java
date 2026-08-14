package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDeliveryLogAware;

/**
 * Resolves the {@link TaskDeliveryLog} used for a workflow aggregate, implemented by
 * the platform integrations and invoked by the core AT STARTUP (see
 * {@link MigrationProcessService#validateTaskDeliveryLogAtStartup()}). The resolution
 * mirrors {@link PhaseTwoOutboxResolver} - a delivery record has to ride the
 * aggregate's own transaction just like an outbox entry:
 * <ol>
 * <li>the most specific {@link TaskDeliveryLogAware} bean covering the aggregate class
 * (selection via {@link AwareSelection}),</li>
 * <li>the platform's default selection: the single available log bean, or - if several
 * exist - the platform-default log matching the persistence technology managing the
 * aggregate,</li>
 * <li><code>null</code> if no log is available at all - the core then reports at
 * startup that deliveries are not deduplicated, naming the remedies.</li>
 * </ol>
 */
public interface TaskDeliveryLogResolver {

  /**
   * Resolves the delivery log for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The log or <code>null</code> if none is available
   * @throws IllegalStateException If several logs exist and none can be attributed to
   *           the aggregate - the guiding message names the beans found and the
   *           remedy (provide a {@link TaskDeliveryLogAware} bean)
   */
  TaskDeliveryLog resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Platform-specific remedy lines appended to the core's guiding message when no log
   * is available but the delivering BPMS may repeat deliveries (e.g. which starter or
   * extension enables a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

}
