package io.vanillabp.integration.processservice;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;

import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;

public final class AggregatePersistenceResolver {

  private static final Class<?> APA = AggregatePersistenceAware.class;

  private AggregatePersistenceResolver() {
  }

  /**
   * Computes a specificity distance for an implementation of
   * {@link AggregatePersistenceAware} with respect to the given aggregate type.
   * Smaller values are more specific.
   *
   * @param implClass The class implementing {@link AggregatePersistenceAware}
   * @param aggregateType The given aggregate type
   * @return Distance of aggregateType to generic argument &quot;implClass&quot;
   */
  public static int distance(
      Class<?> implClass,
      Class<?> aggregateType) {

    int rawDistance = rawInterfaceDistance(implClass, APA, new HashSet<>());

    if (rawDistance == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }

    int genericDistance = genericArgumentDistance(implClass, aggregateType);

    // Raw type distance dominates generic distance
    return rawDistance * 1000 + genericDistance;
  }

  /* ---------------------------------------------------------------------- */
  /* Raw interface distance                                                  */
  /* ---------------------------------------------------------------------- */

  private static int rawInterfaceDistance(
      Class<?> current,
      Class<?> targetInterface,
      Set<Class<?>> visited) {

    if (!visited.add(current)) {
      return Integer.MAX_VALUE;
    }

    int best = Integer.MAX_VALUE;

    for (Class<?> iface : current.getInterfaces()) {
      if (iface.equals(targetInterface)) {
        return 0;
      }

      best = Math.min(
          best,
          safePlusOne(
              rawInterfaceDistance(iface, targetInterface, visited)));
    }

    Class<?> superClass = current.getSuperclass();
    if (superClass != null) {
      best = Math.min(
          best,
          safePlusOne(
              rawInterfaceDistance(superClass, targetInterface, visited)));
    }

    return best;
  }

  /* ---------------------------------------------------------------------- */
  /* Generic argument distance                                               */
  /* ---------------------------------------------------------------------- */

  private static int genericArgumentDistance(
      Class<?> implClass,
      Class<?> aggregateType) {

    for (Type iface : implClass.getGenericInterfaces()) {

      if (!(iface instanceof ParameterizedType pt)) {
        continue;
      }

      if (!pt.getRawType().equals(APA)) {
        continue;
      }

      Type arg = pt.getActualTypeArguments()[0];
      return argumentDistance(arg, aggregateType);
    }

    // recurse into superclass
    Class<?> superClass = implClass.getSuperclass();
    if (superClass != null) {
      return genericArgumentDistance(superClass, aggregateType);
    }

    return Integer.MAX_VALUE;
  }

  private static int argumentDistance(
      Type arg,
      Class<?> aggregateType) {

    if (arg instanceof Class<?> argClass) {

      if (argClass.equals(aggregateType)) {
        return 0;
      }

      return Math.min(
          inheritanceDistance(aggregateType, argClass),
          inheritanceDistance(argClass, aggregateType));
    }

    if (arg instanceof WildcardType wc) {

      Type[] upperBounds = wc.getUpperBounds();
      if (upperBounds.length == 1 && upperBounds[0] instanceof Class<?>) {
        return 100 + argumentDistance(
            upperBounds[0], aggregateType);
      }

      return 1000;
    }

    if (arg instanceof TypeVariable<?>) {
      return 500;
    }

    return Integer.MAX_VALUE;
  }

  /* ---------------------------------------------------------------------- */
  /* Inheritance distance                                                    */
  /* ---------------------------------------------------------------------- */

  /**
   * The distance of inheritance between two classes.
   *
   * @param base The less specific class (base class or interface)
   * @param current The more specific class (subclass)
   * @return Number of steps of inheritance between base and current.
   */
  public static int inheritanceDistance(
      Class<?> base,
      Class<?> current) {

    return inheritanceDistance(base, current, new HashSet<>());
  }

  private static int inheritanceDistance(
      Class<?> base,
      Class<?> current,
      Set<Class<?>> visited) {

    if (base.equals(current)) {
      return 0;
    }

    if (!visited.add(current)) {
      return Integer.MAX_VALUE;
    }

    int best = Integer.MAX_VALUE;

    for (Class<?> iface : current.getInterfaces()) {
      int d = inheritanceDistance(base, iface, visited);
      best = Math.min(best, safePlusOne(d));
    }

    Class<?> superClass = current.getSuperclass();
    if (superClass != null) {
      int d = inheritanceDistance(base, superClass, visited);
      best = Math.min(best, safePlusOne(d));
    }

    return best;
  }

  /* ---------------------------------------------------------------------- */
  /* Utilities                                                               */
  /* ---------------------------------------------------------------------- */

  private static int safePlusOne(
      int value) {
    return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
  }
}
