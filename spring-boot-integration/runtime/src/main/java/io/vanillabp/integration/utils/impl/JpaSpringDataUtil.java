package io.vanillabp.integration.utils.impl;

import java.lang.reflect.Field;
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

public class JpaSpringDataUtil implements SpringDataUtil {

  private final Map<Class<?>, JpaRepository<?, Object>> repositoryCache = new HashMap<>();

  private final Map<Class<?>, EntityInformation<?, Object>> entityInformationCache = new HashMap<>();

  private final Repositories repositories;

  private final JpaContext jpaContext;

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

  public String getIdName(
      final Class<?> type) {

    // TODO: also check annotated getter methods
    return Stream
        .iterate(type, Objects::nonNull, this::getSuperclass)
        .flatMap(c -> Stream.of(c.getDeclaredFields()))
        .filter(this::isIdAnnotationPresent)
        .findFirst()
        .map(Field::getName)
        .orElse(null);

  }

  private boolean isIdAnnotationPresent(
      Field field) {

    return field.isAnnotationPresent(jakarta.persistence.Id.class) || field
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
