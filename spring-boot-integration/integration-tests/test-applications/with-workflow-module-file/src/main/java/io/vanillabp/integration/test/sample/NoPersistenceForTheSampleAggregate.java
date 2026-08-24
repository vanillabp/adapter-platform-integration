package io.vanillabp.integration.test.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The persistence of an application which never persists: {@link Aggregate} is a plain
 * class, and the tests booting this sample application ask whether a workflow-module file
 * is found, whether a repackaged JAR starts, which properties win - never whether an
 * aggregate can be saved.
 * <p>
 * They still have to say who owns the aggregate: one without a
 * persistence is reported while the application starts, because the fallback persistence
 * would look for a Spring Data repository, and none of these applications has a reason to
 * have one. This double is that answer, and every method of it fails loudly, so a test
 * which starts persisting for real hears about it rather than silently passing.
 * <p>
 * Imported explicitly rather than picked up by scanning: an application declaring its own
 * double for a specific aggregate class should not have to compete with this one.
 */
@Configuration
public class NoPersistenceForTheSampleAggregate {

  @Bean
  public AggregatePersistenceAware<Object> anyAggregateWithoutItsOwnPersistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // every aggregate is an Object, and at the greatest inheritance distance there
        // is - so a double declared for a specific class always wins over this one
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
