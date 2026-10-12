package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Collection;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;

/**
 * Resolves the {@link PhaseTwoOutbox} which is used for a workflow aggregate. The platform
 * integrations implement it, and the core calls it AT STARTUP (see
 * {@link MigrationProcessService#validatePhaseTwoOutboxAtStartup()}). The platform resolves
 * in this order:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean which covers the aggregate class
 * (picked by {@link AwareSelection}),</li>
 * <li>the platform's default choice: the single available outbox bean, or, if there are
 * several, the platform's default outbox which matches the persistence technology that
 * manages the aggregate,</li>
 * <li><code>null</code> if no outbox is available at all. Then the core fails the startup
 * with a guiding message which includes {@link #remediesDescription()}.</li>
 * </ol>
 */
public interface PhaseTwoOutboxResolver {

  /**
   * Resolves the outbox for aggregates of the given class.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The outbox or <code>null</code> if none is available
   * @throws IllegalStateException If several outboxes exist and none can be assigned to the
   *           aggregate. The guiding message names the beans found and the remedy (provide a
   *           {@link PhaseTwoOutboxAware} bean)
   */
  PhaseTwoOutbox resolveFor(
      Class<?> workflowAggregateClass);

  /**
   * Every store which this application holds, including the ones which a
   * {@link PhaseTwoOutboxAware} bean names for a single workflow aggregate. An application
   * which provides all of its stores that way has no plain store bean at all. So asking for
   * the store beans alone would say "none" about an application which has several.
   * <p>
   * <strong>What the order means:</strong> nothing a caller may build on. The stores come in
   * the order in which the platform lists its beans. The ones which a
   * {@link PhaseTwoOutboxAware} bean names come after the plain store beans. The order is
   * stable within one boot of one application. It says nothing about priority. Only
   * {@link #resolveFor(Class)} says which store serves a workflow aggregate.
   * <p>
   * <strong>The collection is read-only</strong>, and a store appears in it once, however many
   * beans point at it. Callers use it to count the stores of an application, and to recognise
   * the one store which all of its aggregates share. A caller who wants a store for an
   * aggregate asks {@link #resolveFor(Class)}.
   * <p>
   * A store which the platform would never select is not in here. On Quarkus that is a
   * platform default which <code>vanillabp.outbox.jdbc.enabled</code> or
   * <code>vanillabp.outbox.mongo.enabled</code> switched off, or which has no datasource.
   *
   * @return The stores, read-only and possibly empty
   */
  Collection<PhaseTwoOutbox> allStores();

  /**
   * Lines with remedies for this platform. They are added to the core's guiding message
   * when no outbox is available but one is required (for example which starter or extension
   * to add to enable a default implementation).
   *
   * @return The remedies, one per line
   */
  String remediesDescription();

}
