package io.vanillabp.integration.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.spi.AggregatePersistenceAware;

@SpringBootApplication
public class TestApplication {

  public static void main(
      String[] args) {
    SpringApplication.run(TestApplication.class, args);
  }

  /**
   * What this application tests is that a workflow-module file on the global class path is
   * found, and its workflow aggregate is a plain class which is never saved or read.
   * <p>
   * It still needs a persistence, and saying so is the point: since story 114 an aggregate
   * without one is reported while the application starts, and the fallback persistence
   * would look for a Spring Data repository this application has no reason to have. A
   * double which fails on use says what is true here - the aggregate has an owner, and
   * nothing ever asks it for anything.
   */
  @Bean
  public AggregatePersistenceAware<Object> anyAggregateWithoutItsOwnPersistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {
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
        return null;
      }

    };

  }

}
