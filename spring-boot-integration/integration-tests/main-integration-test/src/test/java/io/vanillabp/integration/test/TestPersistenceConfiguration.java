package io.vanillabp.integration.test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Provides a {@link SpringDataUtil} stub so tests not concerned with persistence do
 * not need a database. Any usage of the stub fails loudly.
 */
@Configuration
public class TestPersistenceConfiguration {

  @Bean
  public SpringDataUtil testSpringDataUtil() {

    return new SpringDataUtil() {

      @Override
      public <O> CrudRepository<? super O, Object> getRepository(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> CrudRepository<O, Object> getRepository(
          final Class<O> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <I> I getId(
          final Object entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public String getIdName(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getIdType(
          final Class<?> type) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> O unproxy(
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public <O> boolean isPersistedEntity(
          final Class<O> entityClass,
          final O entity) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

    };

  }

}
