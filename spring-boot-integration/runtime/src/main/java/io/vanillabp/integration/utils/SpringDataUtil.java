package io.vanillabp.integration.utils;

import org.springframework.data.annotation.Id;
import org.springframework.data.repository.CrudRepository;

/**
 * Utility to handle Spring data repositories. The identifier of a workflow entity may be of any
 * type, for example a string or a long.
 */
public interface SpringDataUtil {

  /**
   * Determines the repository for the given object.
   * 
   * @param <O> The entity's expected type.
   * @param entity The entity
   * @return The repository
   */
  <O> CrudRepository<? super O, Object> getRepository(
      O entity);

  /**
   * Determines the repository for the given type.
   * 
   * @param <O> The entity's expected type.
   * @param type The given entity's type.
   * @return The repository
   */
  <O> CrudRepository<O, Object> getRepository(
      Class<O> type);

  /**
   * Determines the entity's object identifier.
   * 
   * @param <I> The type the caller expects the identifier to have. The value is cast
   *          unchecked, so a wrong expectation fails where the caller assigns it, not here.
   * @param entity The entity
   * @return The id
   * @see Id
   */
  <I> I getId(
      Object entity);

  /**
   * Determines the name of the entity's identifier property. The identifier is the one the
   * persistence itself uses. So an annotation at the field and an annotation at the getter
   * both count. Where an entity carries both, the field wins.
   *
   * @param type The given entity's type.
   * @return The entity's object identifier's parameter name.
   * @throws RuntimeException If the entity names no identifier at all. The message says
   *           which annotation is missing and where it belongs.
   */
  String getIdName(
      Class<?> type);

  /**
   * Determines the type of the entity's identifier.
   * 
   * @param type The given entity's type.
   * @return The entity's object identifier's type.
   */
  Class<?> getIdType(
      Class<?> type);

  /**
   * Unwraps the entity from its proxy. If the proxy is not initialized yet, this loads the
   * data.
   * 
   * @param <O> The entity's type
   * @param entity The entity
   * @return The initialized entity
   */
  <O> O unproxy(
      O entity);

  /**
   * Determines whether the given entity was loaded from or saved to the database before.
   * The other case is a plain object, e.g. right before it is saved.
   * 
   * @param <O> the entity's type
   * @param entityClass The entity's class
   * @param entity The entity
   * @return Entity was loaded/persisted from/to DB before
   */
  <O> boolean isPersistedEntity(
      Class<O> entityClass,
      O entity);

}
