package io.vanillabp.integration.test.utils.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import io.vanillabp.integration.utils.config.MongoDbSpringDataUtilAutoConfiguration;
import io.vanillabp.integration.utils.impl.JpaSpringDataUtil;
import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;
import jakarta.persistence.EntityManagerFactory;

/**
 * Validates the conditions of the {@link SpringDataUtil} auto-configurations for
 * JPA-only, MongoDB-only, both-stores and no-store applications.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringDataUtilAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          JpaSpringDataUtilConfiguration.class, MongoDbSpringDataUtilAutoConfiguration.class));

  @Configuration
  static class JpaInfrastructure {

    @Bean
    EntityManagerFactory entityManagerFactory() {
      return mock(EntityManagerFactory.class);
    }

    @Bean
    JpaContext jpaContext() {
      return mock(JpaContext.class);
    }

  }

  @Configuration
  static class MongoInfrastructure {

    @Bean
    MongoDatabaseFactory mongoDatabaseFactory() {
      return mock(MongoDatabaseFactory.class);
    }

    @Bean
    MongoConverter mongoConverter() {
      return mock(MongoConverter.class);
    }

  }

  @Configuration
  static class CustomSpringDataUtil {

    @Bean
    SpringDataUtil customSpringDataUtil() {
      return mock(SpringDataUtil.class);
    }

  }

  @Test
  @DisplayName("JPA-only application gets the JPA-based SpringDataUtil")
  public void jpaOnly() {

    contextRunner
        .withClassLoader(new FilteredClassLoader(MongoRepository.class))
        .withUserConfiguration(JpaInfrastructure.class)
        .run(context -> assertInstanceOf(
            JpaSpringDataUtil.class,
            context.getBean(SpringDataUtil.class)));

  }

  @Test
  @DisplayName("Mongo-only application gets the MongoDB-based SpringDataUtil")
  public void mongoOnly() {

    contextRunner
        .withClassLoader(new FilteredClassLoader(JpaRepository.class))
        .withUserConfiguration(MongoInfrastructure.class)
        .run(context -> assertInstanceOf(
            MongoDbSpringDataUtil.class,
            context.getBean(SpringDataUtil.class)));

  }

  @Test
  @DisplayName("Mongo-only application having spring-data-jpa on the classpath skips JPA cleanly")
  public void mongoOnlyWithJpaOnClasspath() {

    // JpaRepository is on the classpath but no EntityManagerFactory is available
    contextRunner
        .withUserConfiguration(MongoInfrastructure.class)
        .run(context -> assertInstanceOf(
            MongoDbSpringDataUtil.class,
            context.getBean(SpringDataUtil.class)));

  }

  @Test
  @DisplayName("If both JPA and MongoDB are configured, JPA wins deterministically")
  public void bothStores() {

    contextRunner
        .withUserConfiguration(JpaInfrastructure.class, MongoInfrastructure.class)
        .run(context -> {
          assertInstanceOf(
              JpaSpringDataUtil.class,
              context.getBean(SpringDataUtil.class));
          assertTrue(context.getBeansOfType(SpringDataUtil.class).size() == 1);
        });

  }

  @Test
  @DisplayName("Without any store no SpringDataUtil bean is created")
  public void neitherStore() {

    contextRunner
        .run(context -> assertTrue(context
            .getBeansOfType(SpringDataUtil.class)
            .isEmpty()));

  }

  @Test
  @DisplayName("A custom SpringDataUtil bean turns off the auto-configurations")
  public void customBeanWins() {

    contextRunner
        .withUserConfiguration(CustomSpringDataUtil.class, JpaInfrastructure.class, MongoInfrastructure.class)
        .run(context -> {
          assertTrue(context.getBeansOfType(SpringDataUtil.class).size() == 1);
          assertTrue(context.containsBean("customSpringDataUtil"));
        });

  }

}
