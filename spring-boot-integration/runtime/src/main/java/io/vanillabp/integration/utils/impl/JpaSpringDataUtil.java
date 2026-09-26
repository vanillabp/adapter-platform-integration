package io.vanillabp.integration.utils.impl;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.hibernate.Hibernate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.support.Repositories;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * The {@link SpringDataUtil} of workflow aggregates which are JPA entities. Everything it
 * answers it asks Spring Data or Hibernate, so VanillaBP keeps no knowledge of its own about
 * how an aggregate is mapped.
 * <p>
 * A repository is searched for the entity's class and then up its superclasses, because an
 * application may map a hierarchy and declare the repository on a base class. What is found
 * is cached per type: it cannot change while the application runs, and an aggregate is loaded
 * and saved on every task delivery.
 */
public class JpaSpringDataUtil implements SpringDataUtil {

  private final Map<Class<?>, JpaRepository<?, Object>> repositoryCache = new HashMap<>();

  private final Map<Class<?>, EntityInformation<?, Object>> entityInformationCache = new HashMap<>();

  private final Repositories repositories;

  private final JpaContext jpaContext;

  /**
   * Built by {@link io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration},
   * and by an application which wires the persistence itself.
   *
   * @param applicationContext Where the aggregates' repositories are looked up
   * @param jpaContext Answers which entity manager manages a given type, so an application
   *          with more than one persistence unit asks the right one
   */
  public JpaSpringDataUtil(
      final ApplicationContext applicationContext,
      final JpaContext jpaContext) {

    this.repositories = new Repositories(applicationContext);
    this.jpaContext = jpaContext;

  }

  @SuppressWarnings("unchecked")
  public <O> JpaRepository<? super O, Object> getRepository(
      final O object) {

    //noinspection unchecked
    return getRepository((Class<O>) object.getClass());

  }

  @SuppressWarnings("unchecked")
  public <O> JpaRepository<O, Object> getRepository(
      final Class<O> type) {

    synchronized (repositoryCache) {
      if (repositoryCache.containsKey(type)) {
        return (JpaRepository<O, Object>) repositoryCache.get(type);
      }
    }

    Class<? super O> cls = type;
    Optional<Object> repository;
    do {
      repository = repositories.getRepositoryFor(cls);
      cls = repository.isPresent() ? cls : cls.getSuperclass();
    } while (repository.isEmpty() && (cls != Object.class));

    if (repository.isEmpty()) {
      throw new IllegalStateException(
          String.format("No Spring Data repository defined for '%s'!", type.getName()));
    }

    synchronized (repositoryCache) {
      repositoryCache.put(type, (JpaRepository<?, Object>) repository.get());
    }

    return (JpaRepository<O, Object>) repository.get();

  }

  @Override
  public Class<?> getIdType(
      Class<?> type) {

    synchronized (entityInformationCache) {
      if (entityInformationCache.containsKey(type)) {
        return entityInformationCache
            .get(type)
            .getIdType();
      }
    }

    Class<?> cls = type;
    EntityInformation<?, Object> entityInfo;
    try {
      do {
        entityInfo = repositories.getEntityInformationFor(cls);
        cls = entityInfo != null ? cls : cls.getSuperclass();
      } while ((entityInfo == null) && (cls != Object.class));
      if (entityInfo == null) {
        throw new IllegalStateException(
            String.format("Type '%s' is not an entity!", type.getName()));
      }
    } catch (UnsupportedOperationException e) {
      throw new IllegalStateException(
          String.format("Type '%s' is not an entity!", type.getName()));
    }

    synchronized (entityInformationCache) {
      entityInformationCache.put(type, entityInfo);
    }

    return entityInfo.getIdType();

  }

  private Class<?> getSuperclass(
      final Class<?> cls) {

    return cls.getSuperclass();

  }

  /**
   * JPA allows the ID annotation on the field and on the getter, and both are in use. A
   * field wins over a getter, which is the order Spring Data uses as well, so the same
   * aggregate gets the same answer on JPA and on MongoDB.
   *
   * @param type The aggregate's type
   * @return The name of the ID property
   * @throws IllegalStateException If neither a field nor a getter carries the annotation
   */
  public String getIdName(
      final Class<?> type) {

    final var annotatedField = Stream
        .iterate(type, Objects::nonNull, this::getSuperclass)
        .flatMap(c -> Stream.of(c.getDeclaredFields()))
        .filter(this::isIdAnnotationPresent)
        .findFirst()
        .map(Field::getName);
    if (annotatedField.isPresent()) {
      return annotatedField.get();
    }

    return Stream
        .iterate(type, Objects::nonNull, this::getSuperclass)
        .flatMap(c -> Stream.of(c.getDeclaredMethods()))
        .filter(JpaSpringDataUtil::isGetter)
        .filter(this::isIdAnnotationPresent)
        .findFirst()
        .map(JpaSpringDataUtil::propertyNameOf)
        .orElseThrow(() -> new IllegalStateException(
            """
                There is no field and no getter annotated with @jakarta.persistence.Id or \
                @org.springframework.data.annotation.Id in class '%s' or its superclasses! Place \
                the annotation at the aggregate's ID field or at its getter."""
                .formatted(type.getName())));

  }

  private static boolean isGetter(
      final Method method) {

    if (Modifier.isStatic(method.getModifiers())) {
      return false;
    }
    if (method.getParameterCount() > 0) {
      return false;
    }
    final var returnType = method.getReturnType();
    if (returnType == void.class) {
      return false;
    }
    if (method.getName().startsWith("get")) {
      return true;
    }
    if (!method.getName().startsWith("is")) {
      return false;
    }
    return (returnType == boolean.class) || (returnType == Boolean.class);

  }

  private static String propertyNameOf(
      final Method getter) {

    final var name = getter.getName();
    final var withoutPrefix = name.startsWith("get") ? name.substring(3) : name.substring(2);
    return Introspector.decapitalize(withoutPrefix);

  }

  // both annotations are called Id, so neither can be imported: whichever one was would
  // read like the other and the second would have to be written out anyway
  private boolean isIdAnnotationPresent(
      Field field) {

    return field.isAnnotationPresent(jakarta.persistence.Id.class) || field
        .isAnnotationPresent(org.springframework.data.annotation.Id.class);

  }

  // both annotations are called Id, so neither can be imported: whichever one was would
  // read like the other and the second would have to be written out anyway
  private boolean isIdAnnotationPresent(
      Method getter) {

    return getter.isAnnotationPresent(jakarta.persistence.Id.class) || getter
        .isAnnotationPresent(org.springframework.data.annotation.Id.class);

  }

  @SuppressWarnings("unchecked")
  public <I> I getId(
      final Object domainEntity) {

    // resolve the persistence unit responsible for the entity's type to be
    // correct in applications using multiple persistence units
    final var entityClass = Hibernate.getClass(domainEntity);
    final var id = jpaContext
        .getEntityManagerByManagedType(entityClass)
        .getEntityManagerFactory()
        .getPersistenceUnitUtil()
        .getIdentifier(domainEntity);
    if (id == null) {
      return null;
    }
    return (I) id;

  }

  @Override
  public <O> boolean isPersistedEntity(
      final Class<O> entityClass,
      final O entity) {

    final var em = jpaContext
        .getEntityManagerByManagedType(entityClass);
    if (em.contains(entity)) {
      return true;
    }
    final var id = getId(entity);
    if (id == null) {
      return false;
    }
    return em.find(entityClass, id) != null;

  }

  @SuppressWarnings("unchecked")
  @Override
  public <O> O unproxy(
      final O entity) {

    return (O) Hibernate.unproxy(entity);

  }

}
