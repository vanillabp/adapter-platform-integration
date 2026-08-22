package io.vanillabp.integration.test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Persistence for tests not concerned with it, in two parts.
 * <p>
 * A {@link SpringDataUtil} stub, so no database is needed, and every method of it fails
 * loudly. And a catch-all {@link AggregatePersistenceAware} double which stands in for any
 * aggregate no test brought its own double for: it fails just as loudly, but it makes the
 * application say what it is - one WITH a persistence implementation of its own, rather
 * than one whose aggregates have no repository. Since story 114 the platform reports the
 * latter while it starts, and these applications were never that.
 * <p>
 * A test needing its aggregate saved or read declares its own double for that aggregate
 * class. The most specific candidate wins, and {@code Object} is the greatest possible
 * inheritance distance, so any such double beats this one.
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
        // every aggregate is an Object, so this covers the ones no test cares about -
        // and at the greatest inheritance distance there is, which is why a test's own
        // double always wins
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
        // the contract's "not determinable": this double owns the serialized form, as
        // far as it owns anything at all
        return null;
      }

    };

  }

}
