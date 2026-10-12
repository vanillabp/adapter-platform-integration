package io.vanillabp.integration.test.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The persistence of an application which never persists. {@link Aggregate} is a plain
 * class. The tests which boot this sample application ask whether a workflow-module file
 * is found, whether a repackaged JAR starts and which properties win. They never ask
 * whether an aggregate can be saved.
 * <p>
 * They still have to say who owns the aggregate. The start reports an aggregate without
 * a persistence, because the fallback persistence would look for a Spring Data
 * repository. None of these applications has a reason to have one. This double is the
 * answer. Every method of it fails loudly. So a test which really starts to persist hears
 * about it and does not pass silently.
 * <p>
 * Applications import it explicitly, it is not found by scanning. An application which
 * declares its own double for a specific aggregate class should not compete with this one.
 */
@Configuration
public class NoPersistenceForTheSampleAggregate {

  @Bean
  public AggregatePersistenceAware<Object> anyAggregateWithoutItsOwnPersistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
        // every aggregate is an Object, at the greatest inheritance distance there is.
        // So a double declared for a specific class always wins over this one
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
        // "not determinable" in the terms of the contract. This double owns the
        // serialized form, as far as it owns anything at all
        return null;
      }

    };

  }

}
