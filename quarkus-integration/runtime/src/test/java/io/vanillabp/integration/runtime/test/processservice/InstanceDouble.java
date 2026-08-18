package io.vanillabp.integration.runtime.test.processservice;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.enterprise.util.TypeLiteral;

/**
 * A {@link Instance} over a fixed list - enough for the resolvers of the platform, which only
 * stream over their candidates, ask whether one is resolvable, and read the class a bean was
 * declared as from its {@link Handle} (story 80).
 *
 * @param <T> The bean type
 */
final class InstanceDouble<T> implements Instance<T> {

  private final List<T> beans;

  /**
   * The class each bean was declared as, in the order of {@link #beans}, or
   * <code>null</code> to let a handle report no bean metadata at all.
   */
  private final List<Class<?>> declaredClasses;

  private InstanceDouble(
      final List<T> beans,
      final List<Class<?>> declaredClasses) {

    this.beans = beans;
    this.declaredClasses = declaredClasses;

  }

  /**
   * Beans whose declared class is their runtime class - the normal case of a test.
   *
   * @param <T> The bean type
   * @param beans The beans
   * @return The instance
   */
  static <T> Instance<T> of(
      final List<T> beans) {

    return new InstanceDouble<>(
        beans, beans
            .stream()
            .<Class<?>>map(Object::getClass)
            .toList());

  }

  /**
   * Beans whose declared class differs from their runtime class - what a CDI client proxy
   * looks like to the resolver (story 80).
   *
   * @param <T> The bean type
   * @param beans The beans
   * @param declaredClasses The class each bean was declared as, in the order of the beans
   * @return The instance
   */
  static <T> Instance<T> ofDeclaredAs(
      final List<T> beans,
      final List<Class<?>> declaredClasses) {

    return new InstanceDouble<>(beans, declaredClasses);

  }

  /**
   * Beans no bean metadata is available for - the fallback of the resolver has to name the
   * runtime class then.
   *
   * @param <T> The bean type
   * @param beans The beans
   * @return The instance
   */
  static <T> Instance<T> ofWithoutMetadata(
      final List<T> beans) {

    return new InstanceDouble<>(beans, null);

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

    if (beans.size() != 1) {
      throw new IllegalStateException("not exactly one bean: "
          + beans.size());
    }
    return handleOf(0);

  }

  @Override
  public Iterable<? extends Handle<T>> handles() {

    return java.util.stream.IntStream
        .range(0, beans.size())
        .mapToObj(this::handleOf)
        .toList();

  }

  private Handle<T> handleOf(
      final int index) {

    final var bean = beans.get(index);
    final var declaredClass = declaredClasses == null
        ? null
        : declaredClasses.get(index);
    return new Handle<T>() {

      @Override
      public T get() {
        return bean;
      }

      @Override
      public Bean<T> getBean() {
        return declaredClass == null
            ? null
            : new BeanDouble<>(bean, declaredClass);
      }

      @Override
      public void destroy() {
        // nothing to destroy
      }

      @Override
      public void close() {
        // nothing to close
      }

    };

  }

  /**
   * Bean metadata reporting the class a bean was declared as - the one thing the resolvers
   * read from it.
   *
   * @param <T> The bean type
   */
  private record BeanDouble<T>(T bean, Class<?> declaredClass) implements Bean<T> {

    @Override
    public Class<?> getBeanClass() {
      return declaredClass;
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
      return Set.of();
    }

    @Override
    public Set<java.lang.reflect.Type> getTypes() {
      return Set.of(declaredClass);
    }

    @Override
    public Set<java.lang.annotation.Annotation> getQualifiers() {
      return Set.of();
    }

    @Override
    public Class<? extends java.lang.annotation.Annotation> getScope() {
      return jakarta.enterprise.context.Dependent.class;
    }

    @Override
    public String getName() {
      return null;
    }

    @Override
    public Set<Class<? extends java.lang.annotation.Annotation>> getStereotypes() {
      return Set.of();
    }

    @Override
    public boolean isAlternative() {
      return false;
    }

    @Override
    public T create(
        final CreationalContext<T> creationalContext) {
      return bean;
    }

    @Override
    public void destroy(
        final T instance,
        final CreationalContext<T> creationalContext) {
      // nothing to destroy
    }

  }

}
