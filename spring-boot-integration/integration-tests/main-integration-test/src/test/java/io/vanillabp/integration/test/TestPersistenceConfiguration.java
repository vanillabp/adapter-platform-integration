package io.vanillabp.integration.test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Provides a {@link SpringDataUtil} stub so tests not concerned with persistence do
 * not need a database. Any usage of the stub fails loudly.
 * <p>
 * Resolving a repository is not usage, and since story 114 it must not fail: the
 * platform asks for the aggregate's repository while the application starts, so that an
 * application says what is missing instead of failing at its first task. The repository
 * this stub hands out throws from every method it has, which keeps the loud failure
 * exactly where it was - at the first read or write.
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
      @SuppressWarnings("unchecked")
      public <O> CrudRepository<O, Object> getRepository(
          final Class<O> type) {
        // answering the startup question, and nothing else: every method of this
        // repository throws (story 114)
        return (CrudRepository<O, Object>) java.lang.reflect.Proxy
            .newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{
                    CrudRepository.class
        },
                (
                    proxy,
                    method,
                    args) -> {
                  throw new UnsupportedOperationException("no persistence in this test");
                });
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
