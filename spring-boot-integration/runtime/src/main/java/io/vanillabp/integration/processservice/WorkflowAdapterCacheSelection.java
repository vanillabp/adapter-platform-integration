package io.vanillabp.integration.processservice;

import java.util.List;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.spi.WorkflowAdapterCache;

/**
 * Which election cache an application ended up with. On Spring Boot that is a question
 * rather than a lookup: the platform's in-memory default is auto-configured
 * conditionally, but depending on the order in which configuration classes are read it
 * can coexist with the bean an application defines, and then a plain injection by type
 * is ambiguous.
 * <p>
 * The rule is the one an application expects: a cache it wrote itself replaces the
 * default. It is asked twice - by the process services, which hand the cache to the
 * election, and by the meters of the in-memory cache, which must not publish a size
 * while the elections run through somebody else's cache.
 */
public final class WorkflowAdapterCacheSelection {

  private WorkflowAdapterCacheSelection() {

  }

  /**
   * Selects the election cache in use.
   *
   * @param candidates All {@code WorkflowAdapterCache} beans of the application
   * @return The cache to use or <code>null</code> if none exists (elections then probe
   *         every time)
   * @throws IllegalStateException If several application-provided beans exist
   */
  public static WorkflowAdapterCache theCacheInUse(
      final List<WorkflowAdapterCache> candidates) {

    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      return candidates.getFirst();
    }
    final var applicationProvided = candidates
        .stream()
        .filter(candidate -> candidate.getClass() != InMemoryWorkflowAdapterCache.class)
        .toList();
    if (applicationProvided.size() == 1) {
      return applicationProvided.getFirst();
    }
    throw new IllegalStateException(
        """
            Several beans implementing io.vanillabp.integration.spi.WorkflowAdapterCache were \
            found (%s)! Define exactly ONE application-provided bean - it replaces VanillaBP's \
            in-memory default."""
            .formatted(
                candidates
                    .stream()
                    .map(candidate -> candidate.getClass().getName())
                    .toList()));

  }

}
