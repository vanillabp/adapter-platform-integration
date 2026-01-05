package io.vanillabp.integration.test.utils.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import io.vanillabp.integration.test.utils.impl.jpa.DerivedEntity;
import io.vanillabp.integration.test.utils.impl.jpa.Entity;
import io.vanillabp.integration.test.utils.impl.jpa.Entity2;
import io.vanillabp.integration.test.utils.impl.jpa.EntityRepository;
import io.vanillabp.integration.test.utils.impl.jpa.EntityWithoutRepository;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import io.vanillabp.integration.utils.impl.JpaSpringDataUtil;

@DataJpaTest
@ContextConfiguration(classes = JpaSpringDataUtilTest.JpaTestBootConfiguration.class)
@Import(JpaSpringDataUtilConfiguration.class)
class JpaSpringDataUtilTest {

  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = Entity.class)
  @EnableJpaRepositories(basePackageClasses = EntityRepository.class)
  @TestPropertySource(properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb", "spring.datasource.driver-class-name=org.h2.Driver", "spring.jpa.hibernate.ddl-auto=none"
  })
  static class JpaTestBootConfiguration {
  }

  @Autowired
  private SpringDataUtil jpaSpringDataUtil;

  @Autowired
  private EntityRepository entityRepository;

  @Test
  public void testSpringDataUtilBeanIsAvailable() {

    assertNotNull(jpaSpringDataUtil);
    assertTrue(jpaSpringDataUtil.getClass().isAssignableFrom(JpaSpringDataUtil.class));

  }

  @Test
  public void testRepositoryResolution() {

    try {
      jpaSpringDataUtil.getRepository(EntityWithoutRepository.class);
    } catch (final IllegalStateException e) {
      assertEquals("No Spring Data repository defined for '%s'!".formatted(EntityWithoutRepository.class.getName()),
          e.getMessage());
    }

    final var repository = jpaSpringDataUtil.getRepository(Entity.class);
    assertNotNull(repository);
    assertTrue(EntityRepository.class.isAssignableFrom(repository.getClass()));

    final var entity = new Entity();
    entity.setEntityValue("one");

    final var repositoryByUnpersistedEntity = jpaSpringDataUtil.getRepository(entity);

    assertNotNull(repositoryByUnpersistedEntity);
    assertTrue(EntityRepository.class.isAssignableFrom(
        repositoryByUnpersistedEntity.getClass()));

    final var attachedEntity = repositoryByUnpersistedEntity.save(entity);

    final var repositoryByPersistedEntity = jpaSpringDataUtil.getRepository(attachedEntity);

    assertNotNull(repositoryByPersistedEntity);
    assertTrue(EntityRepository.class.isAssignableFrom(
        repositoryByPersistedEntity.getClass()));
  }

  @Test
  public void testGetId() {

    final var entity = new Entity();
    entity.setEntityValue("one");

    final var nullId = jpaSpringDataUtil.getId(entity);
    assertNull(nullId);

    final var attachedEntity = entityRepository.save(entity);
    final var identityId = jpaSpringDataUtil.getId(attachedEntity);
    assertNotNull(identityId);
    assertEquals(1L, identityId);

  }

  @Test
  public void testGetIdName() {

    final var idName = jpaSpringDataUtil.getIdName(Entity.class);
    assertEquals("id", idName);

    final var idName2 = jpaSpringDataUtil.getIdName(Entity2.class);
    assertEquals("entityId", idName2);

  }

  @Test
  public void testGetIdType() {

    final var idType = jpaSpringDataUtil.getIdType(Entity.class);
    assertEquals(Long.class, idType);

    final var id2Type = jpaSpringDataUtil.getIdType(Entity2.class);
    assertEquals(Integer.class, id2Type);

    final var id3Type = jpaSpringDataUtil.getIdType(Entity2.class);
    assertEquals(id2Type, id3Type); // weak testing of caching ;-)

    final var id4Type = jpaSpringDataUtil.getIdType(DerivedEntity.class);
    assertEquals(Long.class, id4Type);

    try {
      jpaSpringDataUtil.getIdType(String.class);
      Assertions.fail("getIdType(String.class) should have thrown an IllegalStateException");
    } catch (final IllegalStateException e) {
      assertEquals("Type '%s' is not an entity!".formatted(String.class.getName()), e.getMessage());
    }

  }

  @Test
  public void testUnproxy() {

    final var entity = new Entity();
    entity.setEntityValue("one");

    final var unproxiedEntity = jpaSpringDataUtil.unproxy(entity);
    assertEquals(entity, unproxiedEntity);

    final var attachedEntity = entityRepository.save(entity);

    final var proxiedEntity = entityRepository.getReferenceById(attachedEntity.getId());

    final var unproxiedAttachedEntity = jpaSpringDataUtil.unproxy(proxiedEntity);
    assertEquals(proxiedEntity, unproxiedAttachedEntity);

  }

  @Test
  public void testIsPersistedEntity() {

    final var entity = new Entity();
    assertFalse(jpaSpringDataUtil.isPersistedEntity(Entity.class, entity));

    final var persistedEntity = entityRepository.save(entity);
    assertTrue(jpaSpringDataUtil.isPersistedEntity(Entity.class, persistedEntity));

    final var entityNotYetLoaded = new Entity();
    entityNotYetLoaded.setId(persistedEntity.getId());
    assertTrue(jpaSpringDataUtil.isPersistedEntity(Entity.class, entityNotYetLoaded));

  }

}
