package io.vanillabp.integration.adapter.migration.processservice;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;

/**
 * Resolves the {@link PhaseTwoOutbox} used for a workflow aggregate, implemented by
 * the platform integrations and invoked by the core AT STARTUP (see
 * {@link MigrationProcessService#validatePhaseTwoOutboxAtStartup()}). The platform's
 * resolution order is:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean covering the aggregate
 * class (selection via {@link AwareSelection}),</li>
 * <li>the platform's default selection: the single available outbox bean, or - if
 * several exist - the platform-default outbox matching the persistence technology
 * managing the aggregate,</li>
 * <li><code>null</code> if no outbox is available at all - the core then fails the
 * startup with a guiding message including {@link #remediesDescription()}.</li>
 * </ol>
 */
public interface PhaseTwoOutboxResolver {

  /**
   * Resolves the outbox for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The outbox or <code>null</code> if none is available
   * @throws IllegalStateException If several outboxes exist and none can be
   *           attributed to the aggregate - the guiding message names the beans
   *           found and the remedy (provide a {@link PhaseTwoOutboxAware} bean)
   */
  PhaseTwoOutbox resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Platform-specific remedy lines appended to the core's guiding message when no
   * outbox is available but one is required (e.g. which starter/extension to add to
   * enable a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

}
