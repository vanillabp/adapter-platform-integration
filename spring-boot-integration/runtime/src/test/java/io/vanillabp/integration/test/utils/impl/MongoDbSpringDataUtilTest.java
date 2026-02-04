package io.vanillabp.integration.test.utils.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.impl.jpa.BaseEntity;
import io.vanillabp.integration.test.utils.impl.mongodb.DerivedEntity;
import io.vanillabp.integration.test.utils.impl.mongodb.Entity;
import io.vanillabp.integration.test.utils.impl.mongodb.Entity2;
import io.vanillabp.integration.test.utils.impl.mongodb.EntityRepository;
import io.vanillabp.integration.test.utils.impl.mongodb.EntityWithoutRepository;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.config.MongoDbSpringDataUtilConfiguration;
import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;
import io.vanillabp.intergration.test.utils.springboot.FullyQualifiedRepositoryBeanNameGenerator;

@Testcontainers
@SpringBootTest(classes = MongoDbSpringDataUtilTest.MongoDbTestConfiguration.class)
@ExtendWith(SuppressOutputExtension.class)
class MongoDbSpringDataUtilTest {

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      //.withLogConsumer(frame -> System.out.println(frame.getUtf8String()))
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @SpringBootConfiguration
  @EnableAutoConfiguration(
      exclude = {
          SslAutoConfiguration.class, // MongoDb is not available via SSL
          WorkflowModuleAutoConfiguration.class, // see resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
          SpringBootMigrationAdapterAutoConfiguration.class // see resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      })
  @EnableMongoRepositories(
      basePackageClasses = EntityRepository.class,
      nameGenerator = FullyQualifiedRepositoryBeanNameGenerator.class)
  // to avoid conflicts due to autoconfiguration:
  @EntityScan(
      basePackageClasses = BaseEntity.class)
  @EnableJpaRepositories(
      basePackageClasses = io.vanillabp.integration.test.utils.impl.jpa.EntityRepository.class,
      nameGenerator = FullyQualifiedRepositoryBeanNameGenerator.class)
  @Import(MongoDbSpringDataUtilConfiguration.class)
  static class MongoTestApplication {
  }

  @TestConfiguration
  static class MongoDbTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl())
      );
    }
  }

  @Autowired
  private SpringDataUtil mongoDbSpringDataUtil;

  @Autowired
  private EntityRepository entityRepository;

  @Test
  public void testSpringDataUtilBeanIsAvailable() {

    assertNotNull(mongoDbSpringDataUtil);
    assertTrue(mongoDbSpringDataUtil.getClass().isAssignableFrom(MongoDbSpringDataUtil.class));

  }

  @Test
  public void testRepositoryResolution() {

    try {
      mongoDbSpringDataUtil.getRepository(EntityWithoutRepository.class);
    } catch (final IllegalStateException e) {
      assertEquals("No Spring Data repository defined for '%s'!".formatted(EntityWithoutRepository.class.getName()),
          e.getMessage());
    }

    final var repository = mongoDbSpringDataUtil.getRepository(Entity.class);
    assertNotNull(repository);
    assertTrue(EntityRepository.class.isAssignableFrom(repository.getClass()));

    final var entity = new Entity();
    entity.setEntityValue("one");

    final var repositoryByUnpersistedEntity = mongoDbSpringDataUtil.getRepository(entity);

    assertNotNull(repositoryByUnpersistedEntity);
    assertTrue(EntityRepository.class.isAssignableFrom(
        repositoryByUnpersistedEntity.getClass()));

    final var attachedEntity = repositoryByUnpersistedEntity.save(entity);

    final var repositoryByPersistedEntity = mongoDbSpringDataUtil.getRepository(attachedEntity);

    assertNotNull(repositoryByPersistedEntity);
    assertTrue(EntityRepository.class.isAssignableFrom(
        repositoryByPersistedEntity.getClass()));
  }

  @Test
  public void testGetId() {

    final var entity = new Entity();
    entity.setEntityValue("one");

    final var nullId = mongoDbSpringDataUtil.getId(entity);
    assertNull(nullId);

    final var attachedEntity = entityRepository.save(entity);
    final var identityId = mongoDbSpringDataUtil.getId(attachedEntity);
    assertNotNull(identityId);

  }

  @Test
  public void testGetIdName() {

    final var idName = mongoDbSpringDataUtil.getIdName(Entity.class);
    assertEquals("id", idName);

    final var idName2 = mongoDbSpringDataUtil.getIdName(Entity2.class);
    assertEquals("entityId", idName2);

  }

  @Test
  public void testGetIdType() {

    final var idType = mongoDbSpringDataUtil.getIdType(Entity.class);
    assertEquals(String.class, idType);

    final var id2Type = mongoDbSpringDataUtil.getIdType(Entity2.class);
    assertEquals(String.class, id2Type);

    final var id3Type = mongoDbSpringDataUtil.getIdType(Entity2.class);
    assertEquals(id2Type, id3Type); // weak testing of caching ;-)

    final var id4Type = mongoDbSpringDataUtil.getIdType(DerivedEntity.class);
    assertEquals(String.class, id4Type);

    try {
      mongoDbSpringDataUtil.getIdType(String.class);
      Assertions.fail("getIdType(String.class) should have thrown an RuntimeException");
    } catch (final RuntimeException e) {
      assertEquals(
          "Class 'java.lang.String' is not an entity known to MongoDb! Maybe you did not place the @org.springframework.data.mongodb.core.mapping.Document annotation at class level?",
          e.getMessage());
    }

  }

  @Test
  public void testUnproxy() {

    final var entity = new Entity();
    entity.setEntityValue("one");

    try {
      mongoDbSpringDataUtil.unproxy(entity);
      Assertions.fail("unproxy(...) should have thrown an UnsupportedOperationException");
    } catch (final UnsupportedOperationException e) {
    }

  }

  @Test
  public void testIsPersistedEntity() {

    final var entity = new Entity();
    assertFalse(mongoDbSpringDataUtil.isPersistedEntity(Entity.class, entity));

    final var persistedEntity = entityRepository.save(entity);
    assertTrue(mongoDbSpringDataUtil.isPersistedEntity(Entity.class, persistedEntity));

    final var entityNotYetLoaded = new Entity();
    entityNotYetLoaded.setId(persistedEntity.getId());
    assertTrue(mongoDbSpringDataUtil.isPersistedEntity(Entity.class, entityNotYetLoaded));

  }

}
