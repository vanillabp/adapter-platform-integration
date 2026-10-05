package io.vanillabp.integration.test.standalone;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

/**
 * The persistence of an application which never persists. The test only asks where the BPMN
 * files were searched, never whether an aggregate can be saved. VanillaBP still wants to
 * know who owns the aggregate, so this double answers, and every method of it fails loudly.
 */
@Configuration
public class NoPersistenceForTheStandaloneAggregate {

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
