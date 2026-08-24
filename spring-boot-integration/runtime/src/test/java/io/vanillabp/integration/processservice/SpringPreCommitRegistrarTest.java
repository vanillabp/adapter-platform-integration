package io.vanillabp.integration.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;

/**
 * A phase-one check of an adapter runs right before the commit, and it runs in the
 * unit of work of the aggregate - which may be one the application brought.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringPreCommitRegistrarTest {

  private static class OrderAggregate {
  }

  private static class LedgerAggregate {
  }

  /** Records what happened in which order. */
  private static final List<String> events = new LinkedList<>();

  @Configuration
  static class PlatformOnly {

    @Bean
    public javax.sql.DataSource dataSource() {

      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();

    }

    @Bean
    public PlatformTransactionManager transactionManager(
        final javax.sql.DataSource dataSource) {

      return new DataSourceTransactionManager(dataSource);

    }

  }

  private static TransactionRunner recordingRunner(
      final String name) {

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

      @Override
      public void beforeCommit(
          final Runnable check) {
        events.add(name
            + " was asked");
        check.run();
      }

    };

  }

  @Test
  @DisplayName("The platform's runner runs the check right before the commit, not at call time")
  public void theCheckRunsBeforeTheCommit() {

    events.clear();
    try (var context = new AnnotationConfigApplicationContext(PlatformOnly.class)) {
      final var platformRunner = new SpringTransactionRunner(
          context.getBean(PlatformTransactionManager.class));
      final var registrar = new SpringPreCommitRegistrar(
          new SpringTransactionRunnerResolver(context, platformRunner));

      new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
          .executeWithoutResult(status -> {
            registrar.beforeCommit(OrderAggregate.class, () -> events.add("check"));
            events.add("still inside the transaction");
          });

      // the check did NOT run when it was handed over, it ran at the end
      assertEquals(List.of("still inside the transaction", "check"), events);
    }

  }

  @Test
  @DisplayName("A failing check aborts the commit")
  public void aFailingCheckAbortsTheCommit() {

    try (var context = new AnnotationConfigApplicationContext(PlatformOnly.class)) {
      final var platformRunner = new SpringTransactionRunner(
          context.getBean(PlatformTransactionManager.class));
      final var registrar = new SpringPreCommitRegistrar(
          new SpringTransactionRunnerResolver(context, platformRunner));

      final var failure = assertThrows(
          IllegalStateException.class,
          () -> new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
              .executeWithoutResult(status -> registrar.beforeCommit(
                  OrderAggregate.class,
                  () -> {
                    throw new IllegalStateException("the task is gone");
                  })));
      assertTrue(failure.getMessage().contains("the task is gone"), failure.getMessage());
    }

  }

  @Test
  @DisplayName("The runner of the aggregate is asked - an application's own one is not bypassed")
  public void theApplicationsRunnerIsAsked() {

    events.clear();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean("ledgerTransactions", TransactionRunnerAware.class,
          () -> new TransactionRunnerAware<LedgerAggregate>() {

            @Override
            public Class<LedgerAggregate> getAggregateClass() {
              return LedgerAggregate.class;
            }

            @Override
            public TransactionRunner getTransactionRunner() {
              return recordingRunner("the application's runner");
            }

          });
      context.refresh();

      final var platformRunner = new SpringTransactionRunner(
          new org.springframework.beans.factory.support.StaticListableBeanFactory()
              .getBeanProvider(PlatformTransactionManager.class));
      final var registrar = new SpringPreCommitRegistrar(
          new SpringTransactionRunnerResolver(context, platformRunner));

      registrar.beforeCommit(LedgerAggregate.class, () -> events.add("check"));

      assertEquals(List.of("the application's runner was asked", "check"), events);
    }

  }

  @Test
  @DisplayName("A runner which does not implement the hook gets the check immediately")
  public void aRunnerWithoutTheHookGetsItImmediately() {

    final var plainRunner = new TransactionRunner() {

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

    final var ran = new java.util.concurrent.atomic.AtomicBoolean();
    plainRunner.beforeCommit(() -> ran.set(true));

    assertTrue(ran.get(), "the default has to run the check, not swallow it");

  }

}
