package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Most-specific-wins selection of "aware" implementations
 * ({@link io.vanillabp.integration.spi.AggregatePersistenceAware},
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutboxAware}) for a workflow
 * aggregate: among all candidates whose aggregate class the given aggregate type is
 * assignable to, the one with the smallest inheritance distance is chosen -
 * superclasses and implemented interfaces are considered. The selection logic is
 * shared by both platform integrations and both aware SPIs (extracted here so it is
 * implemented exactly once).
 */
public final class AwareSelection {

  private AwareSelection() {
    // utility class
  }

  /**
   * Selects the most specific candidate for the given aggregate type: candidates
   * whose aggregate class is not assignable from the aggregate type are filtered
   * out; among the rest the smallest inheritance distance between the candidate's
   * aggregate class and the aggregate type wins.
   *
   * @param <T> The candidate type (an "aware" SPI)
   * @param candidates The candidates to select from
   * @param aggregateClassAccessor Yields a candidate's aggregate class
   * @param aggregateType The workflow aggregate's type
   * @return The most specific candidate or {@link Optional#empty()} if none matches
   */
  public static <T> Optional<T> mostSpecific(
      final Collection<T> candidates,
      final Function<T, Class<?>> aggregateClassAccessor,
      final Class<?> aggregateType) {

    return candidates
        .stream()
        .map(candidate -> Map.entry(
            candidate,
            inheritanceDistance(aggregateClassAccessor.apply(candidate), aggregateType)))
        .filter(candidateEntry -> candidateEntry.getValue() != Integer.MAX_VALUE)
        .min(Comparator.comparingInt(Map.Entry::getValue))
        .map(Map.Entry::getKey);

  }

  /**
   * The distance of inheritance between two classes.
   *
   * @param base The less specific class (base class or interface)
   * @param current The more specific class (subclass)
   * @return Number of steps of inheritance between base and current, or
   *         {@link Integer#MAX_VALUE} if current is not assignable to base
   */
  public static int inheritanceDistance(
      final Class<?> base,
      final Class<?> current) {

    return inheritanceDistance(base, current, new HashSet<>());

  }

  private static int inheritanceDistance(
      final Class<?> base,
      final Class<?> current,
      final Set<Class<?>> visited) {

    if (base.equals(current)) {
      return 0;
    }

    if (!visited.add(current)) {
      return Integer.MAX_VALUE;
    }

    int best = Integer.MAX_VALUE;

    for (final Class<?> iface : current.getInterfaces()) {
      final int distance = inheritanceDistance(base, iface, visited);
      best = Math.min(best, safePlusOne(distance));
    }

    final Class<?> superClass = current.getSuperclass();
    if (superClass != null) {
      final int distance = inheritanceDistance(base, superClass, visited);
      best = Math.min(best, safePlusOne(distance));
    }

    return best;

  }

  private static int safePlusOne(
      final int value) {

    return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;

  }

}
