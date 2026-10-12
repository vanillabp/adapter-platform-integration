package io.vanillabp.integration.test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Persistence for tests which do not care about it. It has two parts.
 * <p>
 * The first is a {@link SpringDataUtil} stub. So no database is needed, and every method of
 * the stub fails loudly. The second is a catch-all {@link AggregatePersistenceAware} double.
 * It stands in for every aggregate which has no double from its test. It fails just as
 * loudly. But it tells the platform that the application HAS a persistence implementation
 * of its own. Otherwise the application would look like one whose aggregates have no
 * repository. The platform reports such an application while it starts, and these
 * applications were never that.
 * <p>
 * A test which needs its aggregate saved or read declares its own double for that
 * aggregate class. The most specific candidate wins. {@code Object} has the greatest
 * possible inheritance distance, so any such double beats this one.
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

  @Bean
  public AggregatePersistenceAware<Object> anyAggregateWithoutItsOwnPersistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // Every aggregate is an Object, so this covers the ones no test cares about.
        // Object has the greatest inheritance distance there is. So a test's own
        // double always wins.
        return Object.class;
      }

      @Override
      public Object save(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object getAggregateId(
          final Object aggregate) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Object loadById(
          final Object aggregateId) {
        throw new UnsupportedOperationException("no persistence in this test");
      }

      @Override
      public Class<?> getAggregateIdType() {
        // null means "not determinable" in the contract. So this double owns the
        // serialized form, as far as it owns anything at all.
        return null;
      }

    };

  }

}
