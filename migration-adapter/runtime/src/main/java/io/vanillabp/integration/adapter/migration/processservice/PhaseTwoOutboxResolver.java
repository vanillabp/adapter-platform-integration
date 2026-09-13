package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Collection;

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
   * Every store this application holds, the ones named for a single workflow aggregate by
   * a {@link PhaseTwoOutboxAware} bean included. An application which provides all of its
   * stores that way has no plain store bean at all, so asking for the store beans alone
   * would say "none" about an application which has several.
   * <p>
   * <strong>What the order means:</strong> nothing a caller may build on. The stores come
   * in the order the platform enumerates its beans, the ones a
   * {@link PhaseTwoOutboxAware} bean names after the plain store beans, and the order is
   * stable within one boot of one application. It says nothing about priority: which store
   * serves a workflow aggregate is {@link #resolveFor(Class)} and only that.
   * <p>
   * <strong>The collection is read-only</strong> and a store appears in it once, however
   * many beans point at it. Callers use it to count the stores an application has and to
   * recognise the one store all of its aggregates share; a caller which wants a store for
   * an aggregate asks {@link #resolveFor(Class)}.
   * <p>
   * A store the platform would never select is not in here - on Quarkus that is a platform
   * default switched off by <code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code> or left without a datasource.
   *
   * @return The stores, read-only and possibly empty
   */
  Collection<PhaseTwoOutbox> allStores();

  /**
   * Platform-specific remedy lines appended to the core's guiding message when no
   * outbox is available but one is required (e.g. which starter/extension to add to
   * enable a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

}
