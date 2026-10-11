package io.vanillabp.integration.test;

import java.util.function.Supplier;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The unit of work for tests which do not care about it, next to
 * {@link TestPersistenceConfiguration} and {@link TestPhaseTwoOutboxConfiguration}.
 * VanillaBP loads the workflow aggregate, calls the handler and saves the aggregate in ONE
 * transaction. At startup it reports when nothing provides that transaction. This is right
 * for an application. It does not help a test whose application has no transaction
 * manager at all. This runner just runs the work.
 * <p>
 * A test about transactions brings a transaction manager instead. Then the platform's own
 * runner is used.
 */
@Configuration
public class TestTransactionRunnerConfiguration {

  @Bean
  TransactionRunner testTransactionRunner() {

    return new TransactionRunner() {

      @Override
      public <T> T requireNew(
          final Supplier<T> work) {

        return work.get();

      }

      @Override
      public <T> T inCurrent(
          final Supplier<T> work) {

        return work.get();

      }

      @Override
      public boolean isRollbackOnly() {

        return false;

      }

    };

  }

}
