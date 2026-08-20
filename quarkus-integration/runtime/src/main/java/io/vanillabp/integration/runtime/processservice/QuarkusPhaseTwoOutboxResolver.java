package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.inject.Instance;

/**
 * Quarkus implementation of the core's {@link PhaseTwoOutboxResolver}: resolves the
 * {@link PhaseTwoOutbox} used for a workflow aggregate so outbox entries always ride
 * the aggregate's own transaction. Resolution order:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean covering the aggregate
 * class,</li>
 * <li>the single active {@link PhaseTwoOutbox} bean if exactly one exists (defaults
 * which are deactivated - <code>vanillabp.outbox.jdbc.enabled</code> /
 * <code>vanillabp.outbox.mongo.enabled</code> - or unusable - no datasource/MongoDB
 * client configured - are not considered), unless it is the platform default of the
 * other technology: an entry written next to the aggregate instead of into its
 * transaction breaks the atomicity this outbox promises, so that ends the boot,</li>
 * <li>with several active outbox beans the platform default matching the technology
 * which manages the aggregate ({@link QuarkusPersistenceTechnology}, read off the
 * persistence VanillaBP resolved for it in story 69).</li>
 * </ol>
 * An application therefore writes a {@link PhaseTwoOutboxAware} bean for two reasons
 * only: it brought the aggregate's persistence itself, so the technology cannot be
 * read off it, or it wants a store of its own for this aggregate. Anything else is
 * attributed by the platform.
 * <p>
 * Which default serves which technology, and whether it is usable at all, is asked through
 * {@link PlatformDefaultStore} rather than by naming the implementations: the MongoDB ones
 * exist only where the MongoDB client extension does, and a native image resolves every
 * referenced method while it is built (story 85).
 */
public class QuarkusPhaseTwoOutboxResolver implements PhaseTwoOutboxResolver {

  private final Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  private final Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  private final QuarkusPersistenceTechnology persistenceTechnology;

  private final boolean jdbcOutboxEnabled;

  private final boolean mongoOutboxEnabled;

  public QuarkusPhaseTwoOutboxResolver(
      final Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares,
      final Instance<PhaseTwoOutbox> phaseTwoOutboxes,
      final QuarkusPersistenceTechnology persistenceTechnology,
      final boolean jdbcOutboxEnabled,
      final boolean mongoOutboxEnabled) {

    this.phaseTwoOutboxAwares = phaseTwoOutboxAwares;
    this.phaseTwoOutboxes = phaseTwoOutboxes;
    this.persistenceTechnology = persistenceTechnology;
    this.jdbcOutboxEnabled = jdbcOutboxEnabled;
    this.mongoOutboxEnabled = mongoOutboxEnabled;

  }

  @Override
  public PhaseTwoOutbox resolveFor(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific PhaseTwoOutboxAware bean covering the aggregate class
    final List<PhaseTwoOutboxAware<?>> awares = phaseTwoOutboxAwares
        .stream()
        .<PhaseTwoOutboxAware<?>>map(aware -> aware)
        .toList();
    final var mostSpecificAware = AwareSelection.mostSpecific(
        awares,
        PhaseTwoOutboxAware::getAggregateClass,
        workflowAggregateClass);
    if (mostSpecificAware.isPresent()) {
      return mostSpecificAware
          .get()
          .getPhaseTwoOutbox();
    }

    // 2./3. attribute by the technology managing the aggregate
    final var outboxes = phaseTwoOutboxes
        .stream()
        .filter(this::isActive)
        .toList();
    if (outboxes.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. exactly one outbox: use it - unless it is the platform default of the other
    // technology, which would write the entry outside the aggregate's transaction
    if (outboxes.size() == 1) {
      final var outbox = outboxes.getFirst();
      if (mismatches(outbox, technology)) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes));
      }
      return outbox;
    }

    // 3. several outboxes: the platform default of the aggregate's technology
    return outboxes
        .stream()
        .filter(outbox -> matches(outbox, technology))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes)));

  }

  /**
   * Whether an outbox is the platform default serving the given technology. An outbox
   * of the application matches nothing: only its own
   * {@link PhaseTwoOutboxAware} bean says which aggregates it serves.
   */
  private static boolean matches(
      final PhaseTwoOutbox outbox,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (outbox instanceof final PlatformDefaultStore store) && (store.technology() == technology);

  }

  /**
   * Whether an outbox is the platform default of the OTHER technology - the one case in
   * which a single outbox bean must not be used.
   */
  private static boolean mismatches(
      final PhaseTwoOutbox outbox,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (technology != QuarkusPersistenceTechnology.Technology.UNKNOWN) && (outbox instanceof final PlatformDefaultStore store) && (store
        .technology() != technology);

  }

  private static String buildAttributionErrorMessage(
      final Class<?> workflowAggregateClass,
      final QuarkusPersistenceTechnology.Technology technology,
      final List<PhaseTwoOutbox> outboxes) {

    return """
        The PhaseTwoOutbox beans %s cannot be attributed to workflow aggregate '%s' (persistence \
        technology detected: %s)! Outbox entries must be enlisted in the transaction persisting the \
        aggregate. To solve this either
        - provide a bean implementing io.vanillabp.integration.spi.PhaseTwoOutboxAware for this \
        aggregate (returning the outbox matching its persistence), or
        - enable the platform default matching the aggregate's persistence (add the corresponding \
        extension; check 'vanillabp.outbox.jdbc.enabled' / 'vanillabp.outbox.mongo.enabled')."""
        .formatted(
            outboxes
                .stream()
                .map(outbox -> outbox.getClass().getName())
                .toList(),
            workflowAggregateClass.getName(),
            technology);

  }

  @Override
  public String remediesDescription() {

    return """
        - add the 'quarkus-agroal' extension and configure a JDBC datasource (enables the JDBC default),
        - add the 'quarkus-mongodb-client' extension and configure the MongoDB connection incl. 'quarkus.mongodb.database' (enables the MongoDB default), or""";

  }

  /**
   * Whether the given outbox is active: the platform defaults may be deactivated by
   * configuration (their dispatchers then stay inactive, too) - a deactivated
   * default must not be selected.
   *
   * @param outbox The outbox bean
   * @return Whether the outbox may be selected
   */
  private boolean isActive(
      final PhaseTwoOutbox outbox) {

    if (outbox instanceof final PlatformDefaultStore store) {
      return isEnabled(store.technology()) && store.isAvailable();
    }
    return true;

  }

  /**
   * Whether the default serving a technology was left switched on
   * (<code>vanillabp.outbox.jdbc.enabled</code> /
   * <code>vanillabp.outbox.mongo.enabled</code>).
   */
  private boolean isEnabled(
      final QuarkusPersistenceTechnology.Technology technology) {

    return technology == QuarkusPersistenceTechnology.Technology.MONGO
        ? mongoOutboxEnabled
        : jdbcOutboxEnabled;

  }

}
