package io.vanillabp.integration.runtime.test.processservice;

import java.util.Iterator;
import java.util.List;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

/**
 * A {@link Instance} over a fixed list - enough for the resolvers of the platform, which only
 * stream over their candidates and ask whether one is resolvable.
 *
 * @param <T> The bean type
 */
final class InstanceDouble<T> implements Instance<T> {

  private final List<T> beans;

  private InstanceDouble(
      final List<T> beans) {

    this.beans = beans;

  }

  static <T> Instance<T> of(
      final List<T> beans) {

    return new InstanceDouble<>(beans);

  }

  @Override
  public Iterator<T> iterator() {

    return beans.iterator();

  }

  @Override
  public T get() {

    if (beans.size() != 1) {
      throw new IllegalStateException("not exactly one bean: "
          + beans.size());
    }
    return beans.getFirst();

  }

  @Override
  public Instance<T> select(
      final java.lang.annotation.Annotation... qualifiers) {

    return this;

  }

  @Override
  public <U extends T> Instance<U> select(
      final Class<U> subtype,
      final java.lang.annotation.Annotation... qualifiers) {

    return of(
        beans
            .stream()
            .filter(subtype::isInstance)
            .map(subtype::cast)
            .toList());

  }

  @Override
  public <U extends T> Instance<U> select(
      final TypeLiteral<U> subtype,
      final java.lang.annotation.Annotation... qualifiers) {

    throw new UnsupportedOperationException("not needed by the resolvers");

  }

  @Override
  public boolean isUnsatisfied() {

    return beans.isEmpty();

  }

  @Override
  public boolean isAmbiguous() {

    return beans.size() > 1;

  }

  @Override
  public boolean isResolvable() {

    return beans.size() == 1;

  }

  @Override
  public void destroy(
      final T instance) {

    // nothing to destroy

  }

  @Override
  public Handle<T> getHandle() {

    throw new UnsupportedOperationException("not needed by the resolvers");

  }

  @Override
  public Iterable<? extends Handle<T>> handles() {

    throw new UnsupportedOperationException("not needed by the resolvers");

  }

}
