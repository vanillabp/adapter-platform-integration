package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TransactionsProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.TransactionCoverage;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which transaction the work on a workflow aggregate runs in, and
 * what the startup check makes of what the platform reports about it.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class TransactionRunnerResolutionTest {

  @Mock
  private MigratableProcessService<Object> adapterProcessService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistence;

  private static final TransactionRunner NO_TRANSACTION_AT_ALL = new TransactionRunner() {

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

  private MigrationAdapterProperties properties(
      final TransactionsProperties.UnguardedAggregateWrites globally,
      final TransactionsProperties.UnguardedAggregateWrites forTheModule) {

    final var moduleProperties = WorkflowModuleAdapterProperties
        .builder()
        .build();
    if (forTheModule != null) {
      final var transactions = new TransactionsProperties();
      transactions.setUnguardedAggregateWrites(forTheModule);
      moduleProperties.setTransactions(transactions);
    }
    final var transactions = new TransactionsProperties();
    transactions.setUnguardedAggregateWrites(globally);
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .workflowModules(Map.of("test-module", moduleProperties))
        .transactions(transactions)
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Object> processService(
      final MigrationAdapterProperties properties,
      final TransactionRunnerResolver resolver) {

    when(adapterProcessService.getAdapterId()).thenReturn("test-adapter");
    return new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, properties, aggregatePersistence, List
            .of(adapterProcessService), null, null, null, resolver);

  }

  /**
   * A resolver answering one runner and one verdict, standing in for a platform.
   */
  private record StubResolver(TransactionRunner runner,
                              TransactionCoverage coverage) implements TransactionRunnerResolver {

    @Override
    public TransactionRunner resolveFor(
        final Class<?> workflowAggregateClass) {
      return runner;
    }

    @Override
    public String remediesDescription() {
      return "- do what the platform suggests,";
    }

    @Override
    public String describeResolutionFor(
        final Class<?> workflowAggregateClass) {
      return "the stub of this test";
    }

    @Override
    public TransactionCoverage coverageOf(
        final Class<?> workflowAggregateClass) {
      return coverage;
    }

  }

  @Test
  @DisplayName("The resolved runner is used and cached, the caller's runner is the fallback")
  public void resolvedRunnerWins() {

    final var resolved = NO_TRANSACTION_AT_ALL;
    final var fallback = new TransactionRunner() {

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

    final var withResolver = processService(
        properties(null, null),
        new StubResolver(resolved, TransactionCoverage.covered()));
    assertSame(resolved, withResolver.getTransactionRunner(fallback));

    // no resolver at all (tests, adapters handing their runner in): the caller's runner
    final var withoutResolver = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, properties(null, null), aggregatePersistence, List
            .of(adapterProcessService), null);
    assertSame(fallback, withoutResolver.getTransactionRunner(fallback));

  }

  @Test
  @DisplayName("A resolver knowing nothing lets the caller's runner through")
  public void unresolvedFallsBackToTheCallersRunner() {

    final var fallback = NO_TRANSACTION_AT_ALL;
    final var testee = processService(
        properties(null, null),
        new StubResolver(null, TransactionCoverage.unknown()));

    assertSame(fallback, testee.getTransactionRunner(fallback));

  }

  @Test
  @DisplayName("No runner at all ends the startup, naming the platform's remedies and both SPIs")
  public void noRunnerEndsTheStartup() {

    // a remote BPMS: the aggregate and the outbox entry have to be written in one
    // transaction, so an application without one cannot start a single workflow
    when(adapterProcessService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);

    final var testee = processService(
        properties(null, null),
        new StubResolver(null, TransactionCoverage.unknown()));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        testee::validateTransactionRunnerAtStartup);

    assertTrue(failure.getMessage().contains("test-module"), failure.getMessage());
    assertTrue(failure.getMessage().contains("TestProcess"), failure.getMessage());
    assertTrue(failure.getMessage().contains("do what the platform suggests"), failure.getMessage());
    assertTrue(failure.getMessage().contains("TransactionRunnerAware"), failure.getMessage());

  }

  @Test
  @DisplayName("With an embedded BPMS a missing runner is no reason to refuse the startup")
  public void embeddedBpmsNeedsNoRunnerOfItsOwn() {

    // the engine owns the transaction and VanillaBP joins it, so nothing is demanded here
    when(adapterProcessService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    processService(
        properties(null, null),
        new StubResolver(null, TransactionCoverage.unknown()))
        .validateTransactionRunnerAtStartup();

  }

  @Test
  @DisplayName("A store which cannot be covered ends the startup and says how to accept it")
  public void uncoverableEndsTheStartup() {

    final var testee = processService(
        properties(null, null),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.uncoverable("the aggregate is unguarded")));

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        testee::validateTransactionRunnerAtStartup);

    assertTrue(failure.getMessage().contains("the aggregate is unguarded"), failure.getMessage());
    assertTrue(
        failure
            .getMessage()
            .contains("vanillabp.workflow-modules.test-module.transactions.unguarded-aggregate-writes"),
        failure.getMessage());

  }

  @Test
  @DisplayName("Accepting unguarded writes turns the refusal into a startup which continues")
  public void acceptedUnguardedWritesLetTheStartupPass() {

    // globally accepted
    processService(
        properties(TransactionsProperties.UnguardedAggregateWrites.ACCEPTED, null),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.uncoverable("unguarded")))
        .validateTransactionRunnerAtStartup();

    // accepted for this module only
    processService(
        properties(TransactionsProperties.UnguardedAggregateWrites.REJECTED,
            TransactionsProperties.UnguardedAggregateWrites.ACCEPTED),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.uncoverable("unguarded")))
        .validateTransactionRunnerAtStartup();

    // and the module's rejection wins over a global acceptance
    final var moduleRejects = processService(
        properties(TransactionsProperties.UnguardedAggregateWrites.ACCEPTED,
            TransactionsProperties.UnguardedAggregateWrites.REJECTED),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.uncoverable("unguarded")));
    assertThrowsExactly(IllegalStateException.class, moduleRejects::validateTransactionRunnerAtStartup);

  }

  @Test
  @DisplayName("A store known to be weaker only warns, and an unjudged one says nothing")
  public void unguardedAndUnknownVerdictsLetTheStartupPass() {

    processService(
        properties(null, null),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.unguarded("weaker guarantees")))
        .validateTransactionRunnerAtStartup();

    processService(
        properties(null, null),
        new StubResolver(NO_TRANSACTION_AT_ALL, TransactionCoverage.unknown()))
        .validateTransactionRunnerAtStartup();

  }

  @Test
  @DisplayName("The verdict shapes carry their message")
  public void verdictShapes() {

    assertEquals(TransactionCoverage.Verdict.COVERED, TransactionCoverage.covered().verdict());
    assertEquals(TransactionCoverage.Verdict.UNKNOWN, TransactionCoverage.unknown().verdict());
    assertEquals("m", TransactionCoverage.unguarded("m").message());
    assertEquals("m", TransactionCoverage.uncoverable("m").message());

  }

}
