package io.vanillabp.integration.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.processservice.SpringPersistenceTechnology.Technology;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;

/**
 * Story 70 on Spring Boot: which runner serves an aggregate, and what the platform says
 * about the transaction covering its store. The persistence technology is handed in, so the
 * verdicts are pinned without a database.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SpringTransactionRunnerResolverTest {

  private interface HasWorkflowState {
  }

  private static class OrderAggregate implements HasWorkflowState {
  }

  private static class ShipmentAggregate implements HasWorkflowState {
  }

  /**
   * A relational transaction manager, without the infrastructure a real one would want: what
   * the resolver looks at is the type, and "not a MongoTransactionManager" is the point.
   */
  private static class RelationalTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(
        final TransactionDefinition definition) {
      throw new UnsupportedOperationException("no transaction is opened in this test");
    }

    @Override
    public void commit(
        final TransactionStatus status) {
      throw new UnsupportedOperationException("no transaction is opened in this test");
    }

    @Override
    public void rollback(
        final TransactionStatus status) {
      throw new UnsupportedOperationException("no transaction is opened in this test");
    }

  }

  private static final TransactionRunner APPLICATION_RUNNER = new TransactionRunner() {

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

  private static TransactionRunnerAware<?> awareFor(
      final Class<?> aggregateClass) {

    return new TransactionRunnerAware<Object>() {

      @SuppressWarnings("unchecked")
      @Override
      public Class<Object> getAggregateClass() {
        return (Class<Object>) aggregateClass;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return APPLICATION_RUNNER;
      }

    };

  }

  /**
   * A context with the given beans, plus a platform runner built from the given manager
   * (<code>null</code> meaning "this application has none").
   */
  private SpringTransactionRunnerResolver resolver(
      final PlatformTransactionManager manager,
      final Technology technology,
      final java.util.Map<String, Object> beans) {

    final var context = new GenericApplicationContext();
    beans.forEach((
        name,
        bean) -> context.registerBean(name, Object.class, () -> bean));
    if (manager != null) {
      context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
    }
    context.refresh();
    final var platformRunner = manager != null
        ? new SpringTransactionRunner(manager)
        : new SpringTransactionRunner(
            context.getBeanProvider(PlatformTransactionManager.class));
    return new SpringTransactionRunnerResolver(context, platformRunner, aggregate -> technology);

  }

  @Test
  @DisplayName("An aware bean naming an interface serves every aggregate implementing it")
  public void awareOnAnInterfaceServesTheWholeModule() {

    final var testee = resolver(
        new RelationalTransactionManager(),
        Technology.JPA,
        java.util.Map.of("moduleWideTransactions", awareFor(HasWorkflowState.class)));

    assertSame(APPLICATION_RUNNER, testee.resolveFor(OrderAggregate.class));
    assertSame(APPLICATION_RUNNER, testee.resolveFor(ShipmentAggregate.class));
    assertTrue(
        testee.describeResolutionFor(OrderAggregate.class).contains("moduleWideTransactions"),
        testee.describeResolutionFor(OrderAggregate.class));
    // the application owns the transaction, so VanillaBP does not judge what it covers
    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("Two aware beans at the same distance end the startup naming both")
  public void ambiguousAwareBeansEndTheStartup() {

    final var testee = resolver(
        new RelationalTransactionManager(),
        Technology.JPA,
        java.util.Map
            .of(
                "byState", awareFor(HasWorkflowState.class),
                "byObject", awareFor(HasWorkflowState.class)));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.resolveFor(OrderAggregate.class));

    assertTrue(failure.getMessage().contains("byState"), failure.getMessage());
    assertTrue(failure.getMessage().contains("byObject"), failure.getMessage());

  }

  @Test
  @DisplayName("A runner bean of the application serves every aggregate no aware bean covers")
  public void applicationRunnerBeanIsTheSecondStep() {

    final var testee = resolver(
        null,
        Technology.UNKNOWN,
        java.util.Map.of("ledgerTransactions", APPLICATION_RUNNER));

    assertSame(APPLICATION_RUNNER, testee.resolveFor(OrderAggregate.class));
    assertTrue(
        testee.describeResolutionFor(OrderAggregate.class).contains("ledgerTransactions"),
        testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("Several runner beans without an attribution end the startup")
  public void severalRunnerBeansEndTheStartup() {

    final var testee = resolver(
        null,
        Technology.UNKNOWN,
        java.util.Map
            .of(
                "ledgerTransactions", APPLICATION_RUNNER,
                "eventStoreTransactions", APPLICATION_RUNNER));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.resolveFor(OrderAggregate.class));

    assertTrue(failure.getMessage().contains("TransactionRunnerAware"), failure.getMessage());

  }

  @Test
  @DisplayName("Without any transaction manager nothing is left, and the remedies say what to do")
  public void nothingIsLeftWithoutAManager() {

    final var testee = resolver(null, Technology.UNKNOWN, java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertNull(testee.resolveFor(OrderAggregate.class));
    assertTrue(
        testee.remediesDescription().contains("MongoTransactionManager"),
        testee.remediesDescription());
    assertTrue(
        testee.describeResolutionFor(OrderAggregate.class).contains("no transaction is available"),
        testee.describeResolutionFor(OrderAggregate.class));

  }

  @Test
  @DisplayName("A JPA aggregate with a JPA manager is covered")
  public void jpaAggregateWithJpaManagerIsCovered() {

    final var testee = resolver(new RelationalTransactionManager(), Technology.JPA, java.util.Map.of());

    assertEquals(
        TransactionCoverage.Verdict.COVERED,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("A MongoDB aggregate with only a JPA manager cannot be covered, and the fix has a name")
  public void mongoAggregateWithJpaManagerOnlyIsUncoverable() {

    final var testee = resolver(new RelationalTransactionManager(), Technology.MONGO, java.util.Map.of());

    final var coverage = testee.coverageOf(OrderAggregate.class);

    assertEquals(TransactionCoverage.Verdict.UNCOVERABLE, coverage.verdict());
    assertTrue(coverage.message().contains("MongoTransactionManager"), coverage.message());
    assertTrue(coverage.message().contains("replica set"), coverage.message());
    assertTrue(coverage.message().contains("TransactionRunnerAware"), coverage.message());
    // the case which cannot be configured away at all belongs into the same message
    assertTrue(coverage.message().contains("embedded Camunda 7"), coverage.message());

  }

  @Test
  @DisplayName("A JPA aggregate with only a MongoDB manager cannot be covered either")
  public void jpaAggregateWithMongoManagerOnlyIsUncoverable() {

    // a mock keeps the real type as its superclass, which is what the resolver looks at
    final var mongoManager = org.mockito.Mockito
        .mock(org.springframework.data.mongodb.MongoTransactionManager.class);
    final var testee = resolver(mongoManager, Technology.JPA, java.util.Map.of());

    final var coverage = testee.coverageOf(OrderAggregate.class);

    assertEquals(TransactionCoverage.Verdict.UNCOVERABLE, coverage.verdict());
    assertTrue(coverage.message().contains("managed by JPA"), coverage.message());

  }

  @Test
  @DisplayName("A MongoDB aggregate with a MongoDB manager is covered")
  public void mongoAggregateWithMongoManagerIsCovered() {

    final var mongoManager = org.mockito.Mockito
        .mock(org.springframework.data.mongodb.MongoTransactionManager.class);
    final var testee = resolver(mongoManager, Technology.MONGO, java.util.Map.of());

    // the replica-set probe finds no MongoTemplate in this context and does not invent a
    // verdict it cannot support
    assertEquals(
        TransactionCoverage.Verdict.COVERED,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("An aggregate whose persistence the platform cannot judge gets no verdict")
  public void unknownTechnologyGetsNoVerdict() {

    final var testee = resolver(new RelationalTransactionManager(), Technology.UNKNOWN, java.util.Map.of());

    assertEquals(
        TransactionCoverage.Verdict.UNKNOWN,
        testee.coverageOf(OrderAggregate.class).verdict());

  }

  @Test
  @DisplayName("With several transaction managers the remedies name the attribution beans")
  public void severalManagersAreNamedInTheRemedies() {

    final var context = new GenericApplicationContext();
    context.registerBean(
        "transactionManager", PlatformTransactionManager.class, RelationalTransactionManager::new);
    context.registerBean(
        "otherTransactionManager", PlatformTransactionManager.class, RelationalTransactionManager::new);
    context.refresh();
    final var testee = new SpringTransactionRunnerResolver(
        context, new SpringTransactionRunner(
            context.getBeanProvider(PlatformTransactionManager.class)), aggregate -> Technology.JPA);

    assertTrue(testee.remediesDescription().contains("several transaction managers"), testee.remediesDescription());
    assertTrue(testee.remediesDescription().contains("transactionManager"), testee.remediesDescription());
    assertTrue(testee.remediesDescription().contains("TransactionRunnerAware"), testee.remediesDescription());
    // none of them is unique, so nothing is resolvable without an attribution
    org.junit.jupiter.api.Assertions.assertNull(testee.resolveFor(OrderAggregate.class));

  }

}
