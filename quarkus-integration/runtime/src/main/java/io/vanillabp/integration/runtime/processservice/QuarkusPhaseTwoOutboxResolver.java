package io.vanillabp.integration.runtime.processservice;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import jakarta.enterprise.inject.Instance;

/**
 * The Quarkus implementation of the core's {@link PhaseTwoOutboxResolver}. It resolves the
 * {@link PhaseTwoOutbox} for a workflow aggregate. So outbox entries are always written in
 * the aggregate's own transaction. The order of resolution:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean which covers the aggregate
 * class,</li>
 * <li>the single active {@link PhaseTwoOutbox} bean, if there is exactly one. Defaults
 * which are switched off (<code>vanillabp.outbox.jdbc.enabled</code>,
 * <code>vanillabp.outbox.mongo.enabled</code>) or cannot be used (no datasource or MongoDB
 * client configured) do not count. The single bean is not used if it is the platform
 * default of the other technology. An entry written next to the aggregate, and not into its
 * transaction, breaks the atomicity which this outbox promises. So that ends the boot,</li>
 * <li>with several active outbox beans, the platform default which matches the technology
 * that manages the aggregate ({@link QuarkusPersistenceTechnology}, read from the
 * persistence which VanillaBP resolved for it).</li>
 * </ol>
 * So an application writes a {@link PhaseTwoOutboxAware} bean for only two reasons. It
 * brought the aggregate's persistence itself, so the technology cannot be read from it. Or
 * it wants a store of its own for this aggregate. The platform assigns everything else.
 * <p>
 * Which default serves which technology, and whether it can be used at all, is asked
 * through {@link PlatformDefaultStore}. The implementations are not named. The MongoDB ones
 * exist only where the MongoDB client extension does. And a native image resolves every
 * referenced method while it is built.
 * <p>
 * The delivery-log resolver next to this one walks the same three steps. The two stay apart
 * on purpose. They look up different bean types, they answer with different stores, and
 * each message names the properties of its own store. A shared helper for the steps would
 * need all of that as input. And every reader of either resolver would have to open it to
 * see what happens.
 */
public class QuarkusPhaseTwoOutboxResolver implements PhaseTwoOutboxResolver {

  private final Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  private final Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  private final QuarkusPersistenceTechnology persistenceTechnology;

  private final boolean jdbcOutboxEnabled;

  private final boolean mongoOutboxEnabled;

  /**
   * {@link PhaseTwoOutboxResolverProducer} creates it, once per application. The two switches
   * arrive as booleans and not as a configuration object. So this class can be tested
   * without a Quarkus configuration (see {@code QuarkusStoreAttributionTest}).
   *
   * @param phaseTwoOutboxAwares What the application says about its aggregates, if anything
   * @param phaseTwoOutboxes Every outbox bean of the application, the platform defaults
   *          included
   * @param persistenceTechnology Where an aggregate's technology is read from
   * @param jdbcOutboxEnabled Whether <code>vanillabp.outbox.jdbc.enabled</code> was left on
   * @param mongoOutboxEnabled Whether <code>vanillabp.outbox.mongo.enabled</code> was left on
   */
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

    // 2. and 3.: choose by the technology which manages the aggregate
    final var outboxes = phaseTwoOutboxes
        .stream()
        .filter(this::isActive)
        .toList();
    if (outboxes.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. Exactly one outbox: use it. But not if it is the platform default of the other
    // technology, because that would write the entry outside the aggregate's transaction.
    if (outboxes.size() == 1) {
      final var outbox = outboxes.getFirst();
      if (mismatches(outbox, technology)) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes));
      }
      return outbox;
    }

    // 3. Several outboxes: the platform default of the aggregate's technology.
    return outboxes
        .stream()
        .filter(outbox -> matches(outbox, technology))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes)));

  }

  /**
   * Every store which this application holds: first the active {@link PhaseTwoOutbox}
   * beans, then the stores which a {@link PhaseTwoOutboxAware} bean names for one aggregate.
   * A store which is found both ways shows up once. A platform default which is switched off
   * or has nothing to write to is left out. The resolver would never hand it out either.
   */
  @Override
  public Collection<PhaseTwoOutbox> allStores() {

    return Stream
        .concat(
            phaseTwoOutboxes
                .stream()
                .filter(this::isActive),
            phaseTwoOutboxAwares
                .stream()
                .map(PhaseTwoOutboxAware::getPhaseTwoOutbox))
        .filter(Objects::nonNull)
        .collect(
            LinkedHashSet<PhaseTwoOutbox>::new,
            LinkedHashSet::add,
            LinkedHashSet::addAll)
        .stream()
        .toList();

  }

  /**
   * Whether an outbox is the platform default which serves the given technology. An outbox
   * of the application matches nothing. Only its own {@link PhaseTwoOutboxAware} bean says
   * which aggregates it serves.
   */
  private static boolean matches(
      final PhaseTwoOutbox outbox,
      final QuarkusPersistenceTechnology.Technology technology) {

    return (outbox instanceof final PlatformDefaultStore store) && (store.technology() == technology);

  }

  /**
   * Whether an outbox is the platform default of the OTHER technology. This is the one case
   * in which a single outbox bean must not be used.
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
   * Whether the given outbox is active. The configuration may switch the platform defaults
   * off. Then their dispatchers stay inactive too. A default which is switched off must not
   * be chosen.
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
   * Whether the default which serves a technology was left switched on
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
