package io.vanillabp.integration.runtime.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.runtime.processservice.QuarkusTransactionRunnerResolver;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 70 on Quarkus: which runner serves an aggregate. The platform's own runner is always
 * usable here (JTA is a hard dependency of this extension), so what the resolution has to get
 * right is the order and the ambiguity.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusTransactionRunnerResolverTest {

  private interface HasWorkflowState {
  }

  private static class OrderAggregate implements HasWorkflowState {
  }

  private static final TransactionRunner PLATFORM_RUNNER = passThroughRunner();

  private static final TransactionRunner APPLICATION_RUNNER = passThroughRunner();

  private static TransactionRunner passThroughRunner() {

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

  private static TransactionRunnerAware<?> awareFor(
      final Class<?> aggregateClass,
      final TransactionRunner runner) {

    return new TransactionRunnerAware<Object>() {

      @SuppressWarnings("unchecked")
      @Override
      public Class<Object> getAggregateClass() {
        return (Class<Object>) aggregateClass;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return runner;
      }

    };

  }

  private QuarkusTransactionRunnerResolver resolver(
      final List<TransactionRunnerAware<?>> awares,
      final List<TransactionRunner> runners) {

    return new QuarkusTransactionRunnerResolver(
        InstanceDouble.of(awares), InstanceDouble.of(runners), InstanceDouble
            .of(List.<AggregatePersistenceAware<?>>of()), PLATFORM_RUNNER);

  }

  @Test
  @DisplayName("Without any bean of the application the JTA transaction of Quarkus is used")
  public void platformRunnerIsTheDefault() {

    final var testee = resolver(List.of(), List.of());

    assertSame(PLATFORM_RUNNER, testee.resolveFor(OrderAggregate.class));
    assertTrue(
        testee.describeResolutionFor(OrderAggregate.class).contains("JTA"),
        testee.describeResolutionFor(OrderAggregate.class));
    // no aggregate persistence to judge, so no verdict is invented
    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("A runner bean of the application beats the platform's, an aware bean beats both")
  public void applicationBeansWin() {

    final var withRunnerBean = resolver(List.of(), List.of(APPLICATION_RUNNER));
    assertSame(APPLICATION_RUNNER, withRunnerBean.resolveFor(OrderAggregate.class));

    final var awareRunner = passThroughRunner();
    final var withAware = resolver(
        List.of(awareFor(HasWorkflowState.class, awareRunner)),
        List.of(APPLICATION_RUNNER));
    assertSame(awareRunner, withAware.resolveFor(OrderAggregate.class));
    // the application owns the transaction, so the platform says nothing about it
    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        withAware.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("Two aware beans at the same distance end the startup naming both")
  public void ambiguousAwareBeansEndTheStartup() {

    final var aggregateSpecific = passThroughRunner();
    final var testee = resolver(
        List
            .of(
                awareFor(HasWorkflowState.class, APPLICATION_RUNNER),
                awareFor(OrderAggregate.class, aggregateSpecific)),
        List.of());

    // the bean naming the aggregate itself is closer than the one naming its interface
    assertSame(aggregateSpecific, testee.resolveFor(OrderAggregate.class));

    final var tied = resolver(
        List
            .of(
                awareFor(HasWorkflowState.class, APPLICATION_RUNNER),
                awareFor(HasWorkflowState.class, PLATFORM_RUNNER)),
        List.of());
    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> tied.resolveFor(OrderAggregate.class));
    assertTrue(failure.getMessage().contains("same distance"), failure.getMessage());

  }

  @Test
  @DisplayName("Several runner beans without an attribution end the startup")
  public void severalRunnerBeansEndTheStartup() {

    final var testee = resolver(List.of(), List.of(APPLICATION_RUNNER, passThroughRunner()));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.resolveFor(OrderAggregate.class));

    assertTrue(failure.getMessage().contains("TransactionRunnerAware"), failure.getMessage());

  }

}
