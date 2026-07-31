package io.vanillabp.integration.runtime.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutbox;
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
 * client configured - are not considered),</li>
 * <li>with several active outbox beans a guiding {@link IllegalStateException} is
 * raised: unlike Spring Boot, Quarkus has no platform-side knowledge of which
 * persistence manages an aggregate (aggregate persistence is always provided by the
 * application, see
 * {@link io.vanillabp.integration.spi.AggregatePersistenceAware}), so the
 * application has to attribute aggregates to outboxes via {@link PhaseTwoOutboxAware}
 * beans in mixed-persistence setups.</li>
 * </ol>
 */
public class QuarkusPhaseTwoOutboxResolver implements PhaseTwoOutboxResolver {

  private final Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares;

  private final Instance<PhaseTwoOutbox> phaseTwoOutboxes;

  private final boolean jdbcOutboxEnabled;

  private final boolean mongoOutboxEnabled;

  public QuarkusPhaseTwoOutboxResolver(
      final Instance<PhaseTwoOutboxAware<?>> phaseTwoOutboxAwares,
      final Instance<PhaseTwoOutbox> phaseTwoOutboxes,
      final boolean jdbcOutboxEnabled,
      final boolean mongoOutboxEnabled) {

    this.phaseTwoOutboxAwares = phaseTwoOutboxAwares;
    this.phaseTwoOutboxes = phaseTwoOutboxes;
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

    // 2./3. the single active outbox bean - or a guiding error if ambiguous
    final var outboxes = phaseTwoOutboxes
        .stream()
        .filter(this::isActive)
        .toList();
    if (outboxes.isEmpty()) {
      return null;
    }
    if (outboxes.size() > 1) {
      throw new IllegalStateException(
          """
              Several PhaseTwoOutbox beans exist (%s), but none can be attributed to workflow \
              aggregate '%s'! Outbox entries must be enlisted in the transaction persisting the \
              aggregate. To solve this either
              - provide a bean implementing io.vanillabp.integration.spi.PhaseTwoOutboxAware \
              for this aggregate (returning the outbox matching its persistence), or
              - deactivate the unwanted default outbox ('vanillabp.outbox.jdbc.enabled' / \
              'vanillabp.outbox.mongo.enabled')."""
              .formatted(
                  outboxes
                      .stream()
                      .map(outbox -> outbox.getClass().getName())
                      .toList(),
                  workflowAggregateClass.getName()));
    }
    return outboxes.getFirst();

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

    if (outbox instanceof JdbcPhaseTwoOutbox jdbcOutbox) {
      return jdbcOutboxEnabled && jdbcOutbox.isAvailable();
    }
    if (outbox instanceof MongoPhaseTwoOutbox mongoOutbox) {
      return mongoOutboxEnabled && mongoOutbox.isAvailable();
    }
    return true;

  }

}
