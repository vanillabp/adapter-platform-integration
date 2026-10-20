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

  /**
   * The entity in the form this persistence would write it right now. VanillaBP keeps this
   * form when it loads a workflow aggregate, and asks again right before it saves. If both
   * forms are equal, the aggregate did not change, and VanillaBP does not write it. Two forms
   * are compared with {@link Object#equals(Object)}. So the form has to compare by value,
   * and it must not change when the entity changes later.
   * <p>
   * The default answers <code>null</code>, which means "this persistence cannot tell". Then
   * every save writes. That is right for JPA, because Hibernate checks itself whether an
   * entity changed. The MongoDB implementation answers, because a MongoDB repository
   * replaces the whole document on every save.
   *
   * @param entity The entity
   * @return The entity as it would be written, or <code>null</code> if this persistence
   *         cannot tell
   */
  default Object writtenFormOf(
      final Object entity) {

    return null;

  }

}
