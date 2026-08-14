package io.vanillabp.integration.processservice;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.support.Repositories;

/**
 * Detects which persistence technology manages a workflow aggregate, asked by everything
 * which has to write something in the aggregate's OWN transaction: the phase-two outbox
 * ({@link SpringPhaseTwoOutboxResolver}) and the log of processed task deliveries
 * ({@link SpringTaskDeliveryLogResolver}).
 * <p>
 * The technology is read from the aggregate's Spring Data repository. The repository
 * interfaces are matched BY NAME, so the check works in applications having only one of
 * the Spring Data modules on the classpath.
 */
public class SpringPersistenceTechnology {

  public enum Technology {
    JPA,
    MONGO,
    UNKNOWN
  }

  private static final String JPA_REPOSITORY = "org.springframework.data.jpa.repository.JpaRepository";

  private static final String MONGO_REPOSITORY = "org.springframework.data.mongodb.repository.MongoRepository";

  private final ApplicationContext applicationContext;

  private volatile Repositories repositories;

  public SpringPersistenceTechnology(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;

  }

  /**
   * @param workflowAggregateClass The workflow aggregate's class
   * @return The technology, {@link Technology#UNKNOWN} if the aggregate has no Spring
   *         Data repository (custom persistence)
   */
  public Technology of(
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
            return Technology.JPA;
          }
          if (implementsInterfaceNamed(repo.getClass(), MONGO_REPOSITORY)) {
            return Technology.MONGO;
          }
          return Technology.UNKNOWN;
        })
        .orElse(Technology.UNKNOWN);

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
