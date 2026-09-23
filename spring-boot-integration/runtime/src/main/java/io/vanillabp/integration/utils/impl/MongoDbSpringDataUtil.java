package io.vanillabp.integration.utils.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.support.Repositories;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * The {@link SpringDataUtil} of workflow aggregates which are MongoDB documents. What it
 * answers about a document comes from Spring Data's mapping context, and where a class is not
 * mapped or names no id, the message says which annotation is missing.
 * <p>
 * {@link #unproxy(Object)} throws here. MongoDB hands out no proxies, so a document which was
 * read is complete and there is nothing to initialize.
 */
public class MongoDbSpringDataUtil implements SpringDataUtil {

  private final Map<Class<?>, MongoRepository<?, Object>> repositoryCache = new HashMap<>();

  private final Map<Class<?>, MongoPersistentEntity<?>> persistentEntityCache = new HashMap<>();

  private final Repositories repositories;

  private final MongoConverter mongoConverter;

  /**
   * Built by {@link io.vanillabp.integration.utils.config.MongoDbSpringDataUtilConfiguration},
   * and by an application which wires the persistence itself.
   *
   * @param applicationContext Where the aggregates' repositories are looked up
   * @param mongoDbFactory Read only where <code>mongoConverter</code> is
   *          <code>null</code>: the default converter is built from it
   * @param mongoConverter The application's converter, which is what carries its own
   *          conversions. <code>null</code> where it defines none
   */
  public MongoDbSpringDataUtil(
      final ApplicationContext applicationContext,
      final MongoDatabaseFactory mongoDbFactory,
      @Nullable final MongoConverter mongoConverter) {

    this.repositories = new Repositories(applicationContext);
    this.mongoConverter = mongoConverter == null ? getDefaultMongoConverter(mongoDbFactory) : mongoConverter;

  }

  @Override
  @SuppressWarnings("unchecked")
  public <O> MongoRepository<? super O, Object> getRepository(
      final O object) {

    //noinspection unchecked
    return getRepository((Class<O>) object.getClass());

  }

  @Override
  @SuppressWarnings("unchecked")
  public <O> MongoRepository<O, Object> getRepository(
      final Class<O> type) {

    synchronized (repositoryCache) {
      if (repositoryCache.containsKey(type)) {
        return (MongoRepository<O, Object>) repositoryCache.get(type);
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
      repositoryCache.put(type, (MongoRepository<?, Object>) repository.get());
    }

    return (MongoRepository<O, Object>) repository.get();

  }

  private MongoPersistentEntity<?> getPersistentEntity(
      final Class<?> type) {

    synchronized (persistentEntityCache) {
      if (persistentEntityCache.containsKey(type)) {
        return persistentEntityCache.get(type);
      }
    }

    final var persistentEntity = mongoConverter.getMappingContext().getPersistentEntity(type);
    if (persistentEntity == null) {
      throw new RuntimeException("Class '"
          + type.getName()
          + "' is not an entity known to MongoDb! Maybe you did not place the "
          + "@org.springframework.data.mongodb.core.mapping.Document annotation at class level?");
    }
    if (!persistentEntity.hasIdProperty()) {
      throw new RuntimeException("There is no property or getter annotated with "
          + "@org.springframework.data.annotation.Id in class '"
          + type.getName()
          + "' or its superclasses!");
    }

    synchronized (persistentEntityCache) {
      persistentEntityCache.put(type, persistentEntity);
    }

    return persistentEntity;

  }

  @Override
  public Class<?> getIdType(
      final Class<?> type) {

    final var persistentEntity = getPersistentEntity(type);
    return Objects.requireNonNull(persistentEntity.getIdProperty()).getType();

  }

  public String getIdName(
      final Class<?> type) {

    final var persistentEntity = getPersistentEntity(type);
    return Objects.requireNonNull(persistentEntity.getIdProperty()).getName();

  }

  @Override
  @SuppressWarnings("unchecked")
  public <I> I getId(
      final Object entity) {

    final var persistentEntity = getPersistentEntity(entity.getClass());
    return (I) persistentEntity.getIdentifierAccessor(entity).getIdentifier();

  }

  @Override
  public <O> O unproxy(
      final O entity) {

    throw new UnsupportedOperationException();

  }

  @Override
  public <O> boolean isPersistedEntity(
      final Class<O> entityClass,
      final O entity) {

    final var persistentEntity = getPersistentEntity(entityClass);
    if (persistentEntity.isNew(entity)) {
      return false;
    }

    final var id = persistentEntity.getIdentifierAccessor(entity).getIdentifier();
    assert (id != null);
    return getRepository(entityClass).existsById(id);

  }

  /**
   * @see "MongoTemplate#getDefaultMongoConverter(MongoDatabaseFactory)"
   */
  private static MongoConverter getDefaultMongoConverter(
      final MongoDatabaseFactory factory) {

    DbRefResolver dbRefResolver = new DefaultDbRefResolver(factory);
    MongoCustomConversions conversions = new MongoCustomConversions(Collections.emptyList());
    MongoMappingContext mappingContext = new MongoMappingContext();
    mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    mappingContext.afterPropertiesSet();
    MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mappingContext);
    converter.setCustomConversions(conversions);
    converter.setCodecRegistryProvider(factory);
    converter.afterPropertiesSet();
    return converter;

  }

}
