package io.vanillabp.integration.deployment.processservice;

import java.util.HashSet;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.jboss.jandex.WildcardType;

import io.vanillabp.spi.process.AggregatePersistenceAware;

/**
 * A utility class used to figure out the right aggregate persistence for a given aggregate type.
 */
public final class AggregatePersistenceResolver {

  private static final DotName APA = DotName.createSimple(AggregatePersistenceAware.class);

  private AggregatePersistenceResolver() {
  }

  /**
   * Computes a specificity distance for an implementation of
   * {@link AggregatePersistenceAware} with respect to the given aggregate type.
   * Smaller values are more specific.
   *
   * @param index The index to use for looking up classes
   * @param implClass The class implementing {@link AggregatePersistenceAware}
   * @param aggregateType The given aggregate type
   * @return Distance of aggregateType to generic argument &quot;implClass&quot;
   */
  public static int distance(
      IndexView index,
      ClassInfo implClass,
      DotName aggregateType) {

    int rawDistance = rawInterfaceDistance(index, implClass, APA, new HashSet<>());
    if (rawDistance == Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }

    int genericDistance = genericArgumentDistance(index, implClass, aggregateType);

    // Raw type distance dominates generic distance
    return rawDistance * 1000 + genericDistance;
  }

  /* ---------------------------------------------------------------------- */
  /* Raw interface distance                                                  */
  /* ---------------------------------------------------------------------- */

  private static int rawInterfaceDistance(
      IndexView index,
      ClassInfo current,
      DotName targetInterface,
      Set<DotName> visited) {

    if (!visited.add(current.name())) {
      return Integer.MAX_VALUE;
    }

    int best = Integer.MAX_VALUE;

    for (Type iface : current.interfaceTypes()) {
      if (iface.name().equals(targetInterface)) {
        return 0;
      }

      ClassInfo ifaceInfo = index.getClassByName(iface.name());
      if (ifaceInfo != null) {
        best = Math.min(
            best,
            1 + rawInterfaceDistance(index, ifaceInfo, targetInterface, visited));
      }
    }

    Type superType = current.superClassType();
    if (superType != null) {
      ClassInfo superInfo = index.getClassByName(superType.name());
      if (superInfo != null) {
        best = Math.min(
            best,
            1 + rawInterfaceDistance(index, superInfo, targetInterface, visited));
      }
    }

    return best;
  }

  /* ---------------------------------------------------------------------- */
  /* Generic argument distance                                               */
  /* ---------------------------------------------------------------------- */

  private static int genericArgumentDistance(
      IndexView index,
      ClassInfo implClass,
      DotName aggregateType) {

    for (Type iface : implClass.interfaceTypes()) {

      if (!iface.name().equals(APA)) {
        continue;
      }

      // Raw type: AggregatePersistenceAware
      if (iface.kind() != Type.Kind.PARAMETERIZED_TYPE) {
        return 1000;
      }

      Type arg = iface.asParameterizedType().arguments().getFirst();
      return argumentDistance(index, arg, aggregateType);
    }

    // recurse into superclass
    Type superType = implClass.superClassType();
    if (superType != null) {
      ClassInfo superInfo = index.getClassByName(superType.name());
      if (superInfo != null) {
        return genericArgumentDistance(index, superInfo, aggregateType);
      }
    }

    return Integer.MAX_VALUE;
  }

  private static int argumentDistance(
      IndexView index,
      Type arg,
      DotName aggregateType) {

    switch (arg.kind()) {

      case CLASS:
        if (arg.name().equals(aggregateType)) {
          return 0;
        }
        return Math.min(
            inheritanceDistance(
                index,
                aggregateType,
                arg.name()),
            inheritanceDistance(
                index,
                arg.name(),
                aggregateType));

      case WILDCARD_TYPE:
        WildcardType wc = arg.asWildcardType();
        if (wc.extendsBound() != null) {
          return 100 + argumentDistance(
              index,
              wc.extendsBound(),
              aggregateType);
        }
        return 1000;

      case TYPE_VARIABLE:
        return 500;

      default:
        return Integer.MAX_VALUE;
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Inheritance distance for classes/interfaces                             */
  /* ---------------------------------------------------------------------- */

  /**
   * The distance of inheritance between two classes.
   *
   * @param index The index to use for looking up classes
   * @param base The less specific class (base class or interface)
   * @param current The more specific class (subclass)
   * @return Number of steps of inheritance between base and current.
   */
  public static int inheritanceDistance(
      IndexView index,
      DotName base,
      DotName current) {

    return inheritanceDistance(index, base, current, new HashSet<>());
  }

  private static int inheritanceDistance(
      IndexView index,
      DotName base,
      DotName current,
      Set<DotName> visited) {

    if (base.equals(current)) {
      return 0;
    }

    if (!visited.add(current)) {
      return Integer.MAX_VALUE;
    }

    ClassInfo info = index.getClassByName(current);
    if (info == null) {
      return Integer.MAX_VALUE;
    }

    int best = Integer.MAX_VALUE;

    for (Type iface : info.interfaceTypes()) {
      int interfaceTypeDistance = inheritanceDistance(
          index, base, iface.name(), visited);
      best = Math.min(
          best,
          interfaceTypeDistance == Integer.MAX_VALUE ? interfaceTypeDistance : 1 + interfaceTypeDistance);
    }

    Type superType = info.superClassType();
    if (superType != null) {
      int superTypeDistance = inheritanceDistance(
          index, base, superType.name(), visited);
      best = Math.min(
          best,
          superTypeDistance == Integer.MAX_VALUE ? superTypeDistance : 1 + superTypeDistance);
    }

    return best;
  }

}
