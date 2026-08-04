package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;

@ExtendWith(MockitoExtension.class)
public class ProcessServiceSpringBeanTest {

  @Mock
  private MigratableProcessService<Object> migratableProcessService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistenceAware;

  private ProcessServiceSpringBean<Object> testee;

  @BeforeEach
  public void buildProcessService() {

    lenient().when(migratableProcessService.getAdapterId()).thenReturn("test-adapter");

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();

    testee = new ProcessServiceSpringBean<>(
        "test-module", "TestProcess", Object.class, properties, aggregatePersistenceAware, List
            .of(migratableProcessService), null, null, null);

  }

  @AfterEach
  public void cleanupTransactionState() {

    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

  }

  @Test
  @DisplayName("transactionIsActive reflects an active transaction")
  public void transactionIsActiveReflectsActiveTransaction() {

    assertFalse(testee.transactionIsActive());

    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertTrue(testee.transactionIsActive());

  }

  @Test
  @DisplayName("startWorkflow fails if a transaction is needed but none is active")
  public void startWorkflowFailsWithoutRequiredTransaction() {

    when(migratableProcessService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflow(new Object()));
    // the guiding message has to state the fix
    assertTrue(exception.getMessage().contains("No transaction is active"));
    assertTrue(exception.getMessage().contains("@Transactional"));

  }

  @Test
  @DisplayName("Not yet implemented operations throw UnsupportedOperationException saying so")
  public void notYetImplementedOperationsThrow() {

    final var aggregate = new Object();

    final var operations = java.util.List.<org.junit.jupiter.api.function.Executable>of(
        () -> testee.getProcessDefinitions(aggregate, null),
        () -> testee.getBpmnXml("definition-1"),
        () -> testee.getWorkflowHistory(aggregate, null));

    for (final var operation : operations) {
      final var exception = assertThrowsExactly(
          UnsupportedOperationException.class,
          operation);
      // a silent no-op would hide the missing implementation - the message says so
      assertTrue(exception.getMessage().contains("not yet supported by VanillaBP 2"));
    }

  }

  @Test
  @DisplayName("startWorkflow succeeds if no transaction is needed")
  public void startWorkflowSucceedsIfNoTransactionIsNeeded() {

    when(migratableProcessService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    final var workflowAggregate = new Object();
    final var attachedAggregate = new Object();
    when(aggregatePersistenceAware.save(workflowAggregate)).thenReturn(attachedAggregate);
    when(aggregatePersistenceAware.getAggregateId(attachedAggregate)).thenReturn(42L);

    final var result = testee.startWorkflow(workflowAggregate);

    assertSame(attachedAggregate, result);

  }

}
