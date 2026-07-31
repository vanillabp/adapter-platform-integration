package io.vanillabp.integration.processservice;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.support.Repositories;

import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
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
 * technology managing the aggregate (JPA-managed → the gruelbox default,
 * Mongo-managed → the MongoDB default). The technology is detected from the
 * aggregate's Spring Data repository type.</li>
 * </ol>
 * If no outbox can be attributed, a guiding {@link IllegalStateException} names the
 * beans found and the remedy (provide a {@link PhaseTwoOutboxAware} bean).
 */
public class SpringPhaseTwoOutboxResolver implements PhaseTwoOutboxResolver {

  private enum PersistenceTechnology {
    JPA,
    MONGO,
    UNKNOWN
  }

  private static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";

  private static final String MONGO_REPOSITORY = "org.springframework.data.mongodb.repository.MongoRepository";

  private final ApplicationContext applicationContext;

  private volatile Repositories repositories;

  public SpringPhaseTwoOutboxResolver(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;

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

    final var technology = detectPersistenceTechnology(workflowAggregateClass);

    // 2. exactly one outbox bean: use it - unless it is a platform default clearly
    // not matching the aggregate's persistence (broken atomicity, fail guiding)
    if (outboxes.size() == 1) {
      final var entry = outboxes
          .entrySet()
          .iterator()
          .next();
      final var mismatch = ((technology == PersistenceTechnology.MONGO) && entry
          .getKey()
          .equals(
              GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME)) || ((technology == PersistenceTechnology.JPA) && entry
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
    final var defaultBeanName = switch (technology) {
      case JPA -> GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME;
      case MONGO -> MongoPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME;
      case UNKNOWN -> null;
    };
    return Optional
        .ofNullable(defaultBeanName)
        .map(outboxes::get)
        .orElseThrow(() -> new IllegalStateException(
            buildAttributionErrorMessage(workflowAggregateClass, technology, outboxes.keySet())));

  }

  @Override
  public String remediesDescription() {

    return """
        - add spring-boot-starter-data-jpa and configure a data source (enables the gruelbox-based default),
        - add spring-boot-starter-data-mongodb and configure the MongoDB connection (enables the MongoDB default), or""";

  }

  private String buildAttributionErrorMessage(
      final Class<?> workflowAggregateClass,
      final PersistenceTechnology technology,
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

  /**
   * Detects the persistence technology managing the aggregate from its Spring Data
   * repository's type. The repository interfaces are matched BY NAME so this check
   * works in applications having only one of the Spring Data modules on the
   * classpath.
   *
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The technology, {@link PersistenceTechnology#UNKNOWN} if the aggregate
   *         has no Spring Data repository (custom persistence)
   */
  private PersistenceTechnology detectPersistenceTechnology(
      final Class<?> workflowAggregateClass) {

    if (repositories == null) {
      repositories = new Repositories(applicationContext);
    }
    Class<?> current = workflowAggregateClass;
    Optional<Object> repository = Optional.empty();
    while (repository.isEmpty() && (current != null) && (current != Object.class)) {
      repository = repositories.getRepositoryFor(current);
      current = current.getSuperclass();
    }
    return repository
        .map(repo -> {
          if (implementsInterfaceNamed(repo.getClass(), JPA_REPOSITORY)) {
            return PersistenceTechnology.JPA;
          }
          if (implementsInterfaceNamed(repo.getClass(), MONGO_REPOSITORY)) {
            return PersistenceTechnology.MONGO;
          }
          return PersistenceTechnology.UNKNOWN;
        })
        .orElse(PersistenceTechnology.UNKNOWN);

  }

  private static boolean implementsInterfaceNamed(
      final Class<?> type,
      final String interfaceName) {

    return implementsInterfaceNamed(type, interfaceName, new HashSet<>());

  }

  private static boolean implementsInterfaceNamed(
      final Class<?> type,
      final String interfaceName,
      final Set<Class<?>> visited) {

    if ((type == null) || !visited.add(type)) {
      return false;
    }
    if (type.getName().equals(interfaceName)) {
      return true;
    }
    for (final var iface : type.getInterfaces()) {
      if (implementsInterfaceNamed(iface, interfaceName, visited)) {
        return true;
      }
    }
    return implementsInterfaceNamed(type.getSuperclass(), interfaceName, visited);

  }

}
