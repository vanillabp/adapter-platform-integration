package io.vanillabp.integration.processservice;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;

/**
 * Spring Boot implementation of the core's {@link PhaseTwoOutboxResolver}: resolves
 * the {@link PhaseTwoOutbox} used for a workflow aggregate so outbox entries always
 * ride the aggregate's own transaction (also in mixed-persistence applications).
 * Resolution order:
 * <ol>
 * <li>the most specific {@link PhaseTwoOutboxAware} bean covering the aggregate
 * class,</li>
 * <li>the single {@link PhaseTwoOutbox} bean if exactly one exists - unless it is a
 * platform default NOT matching the aggregate's detectable persistence technology
 * (that mismatch would break the outbox's atomicity guarantee and fails with a
 * guiding message instead),</li>
 * <li>with several outbox beans: the platform-default bean matching the persistence
 * technology managing the aggregate (JPA-managed → the JDBC default, Mongo-managed →
 * the MongoDB default). The technology is detected from the aggregate's Spring Data
 * repository type.</li>
 * </ol>
 * If no outbox can be attributed, a guiding {@link IllegalStateException} names the
 * beans found and the remedy (provide a {@link PhaseTwoOutboxAware} bean).
 */
public class SpringPhaseTwoOutboxResolver implements PhaseTwoOutboxResolver {

  /**
   * The names the JPA default goes by. Two of them, because an application may still run
   * the gruelbox store instead of the one VanillaBP writes itself
   * (<code>vanillabp.outbox.gruelbox.enabled</code>) - never both, the two
   * auto-configurations exclude each other.
   */
  private static final Set<String> JPA_DEFAULT_OUTBOX_BEAN_NAMES = Set.of(
      JdbcPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME,
      GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME);

  private final ApplicationContext applicationContext;

  private final SpringPersistenceTechnology persistenceTechnology;

  public SpringPhaseTwoOutboxResolver(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;
    this.persistenceTechnology = new SpringPersistenceTechnology(applicationContext);

  }

  @Override
  public PhaseTwoOutbox resolveFor(
      final Class<?> workflowAggregateClass) {

    // 1. the most specific PhaseTwoOutboxAware bean covering the aggregate class
    final var awares = applicationContext
        .getBeanProvider(PhaseTwoOutboxAware.class)
        .stream()
        .<PhaseTwoOutboxAware<?>>map(aware -> (PhaseTwoOutboxAware<?>) aware)
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

    final Map<String, PhaseTwoOutbox> outboxes = applicationContext
        .getBeansOfType(PhaseTwoOutbox.class);
    if (outboxes.isEmpty()) {
      return null;
    }

    final var technology = persistenceTechnology.of(workflowAggregateClass);

    // 2. exactly one outbox bean: use it - unless it is a platform default clearly
    // not matching the aggregate's persistence (broken atomicity, fail guiding)
    if (outboxes.size() == 1) {
      final var entry = outboxes
          .entrySet()
          .iterator()
          .next();
      final var mismatch = ((technology == SpringPersistenceTechnology.Technology.MONGO) && JPA_DEFAULT_OUTBOX_BEAN_NAMES
          .contains(entry.getKey())) || ((technology == SpringPersistenceTechnology.Technology.JPA) && entry
              .getKey()
              .equals(MongoPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME));
      if (mismatch) {
        throw new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes.keySet()));
      }
      return entry.getValue();
    }

    // 3. several outbox beans: attribute by the persistence technology managing
    // the aggregate - to THE platform-default bean of that technology
    final var defaultOutbox = switch (technology) {
      case JPA -> JPA_DEFAULT_OUTBOX_BEAN_NAMES
          .stream()
          .map(outboxes::get)
          .filter(Objects::nonNull)
          .findFirst();
      case MONGO -> Optional.ofNullable(outboxes.get(MongoPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME));
      case UNKNOWN -> Optional.<PhaseTwoOutbox>empty();
    };
    return defaultOutbox
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes.keySet())));

  }

  /**
   * Every store this context holds: the {@link PhaseTwoOutbox} beans first, then the
   * stores a {@link PhaseTwoOutboxAware} bean names for one aggregate. A store reached
   * both ways appears once.
   */
  @Override
  public Collection<PhaseTwoOutbox> allStores() {

    return Stream
        .concat(
            applicationContext
                .getBeanProvider(PhaseTwoOutbox.class)
                .stream(),
            applicationContext
                .getBeanProvider(PhaseTwoOutboxAware.class)
                .stream()
                .map(aware -> ((PhaseTwoOutboxAware<?>) aware).getPhaseTwoOutbox()))
        .filter(Objects::nonNull)
        .collect(
            LinkedHashSet<PhaseTwoOutbox>::new,
            LinkedHashSet::add,
            LinkedHashSet::addAll)
        .stream()
        .toList();

  }

  @Override
  public String remediesDescription() {

    return """
        - add spring-boot-starter-data-jpa and configure a data source (enables the JDBC default),
        - add spring-boot-starter-data-mongodb and configure the MongoDB connection (enables the MongoDB default),
        - define a bean implementing io.vanillabp.integration.spi.PhaseTwoOutbox storing entries wherever your workflow aggregates live, or""";

  }

  private String buildAttributionErrorMessage(
      final Class<?> workflowAggregateClass,
      final SpringPersistenceTechnology.Technology technology,
      final Set<String> outboxBeanNames) {

    return """
        The PhaseTwoOutbox beans %s cannot be attributed to workflow aggregate '%s' (persistence \
        technology detected: %s)! Outbox entries must be enlisted in the transaction persisting the \
        aggregate. To solve this either
        - provide a bean implementing io.vanillabp.integration.spi.PhaseTwoOutboxAware for \
        this aggregate (returning the outbox matching its persistence), or
        - enable the platform default matching the aggregate's persistence (add the corresponding \
        Spring Data starter; check 'vanillabp.outbox.jdbc.enabled' / 'vanillabp.outbox.mongo.enabled')."""
        .formatted(
            outboxBeanNames,
            workflowAggregateClass.getName(),
            technology);

  }

}
