package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class ProcessServiceSpringBeanTest {

  /**
   * What a probe is asked about. Any scope does here: the adapters of this
   * test answer from what the test told them, not from a deployment.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

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
            .of(migratableProcessService), null, null, null, null);

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
  @DisplayName("startWorkflow fails if no transaction is active")
  public void startWorkflowFailsWithoutTransaction() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflow(new Object()));
    // the guiding message has to state the fix
    assertTrue(exception.getMessage().contains("No transaction is active"));
    assertTrue(exception.getMessage().contains("@Transactional"));

  }

  @Test
  @DisplayName("The viewer/history API needs no transaction and reports unknown subjects guiding")
  public void viewerApiOperationsNeedNoTransaction() {

    final var aggregate = new Object();
    when(aggregatePersistenceAware.getAggregateId(aggregate)).thenReturn("4711");
    when(migratableProcessService.awarenessOfWorkflow(SCOPE, aggregatePersistenceAware, "4711"))
        .thenReturn(io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS);

    // no transaction is active (see cleanupTransactionState) - reads must not
    // require one; the workflow being unknown is what raises the SPI's exception
    final var workflowOperations = java.util.List.<org.junit.jupiter.api.function.Executable>of(
        () -> testee.getProcessDefinitions(aggregate, null),
        () -> testee.getWorkflowHistory(aggregate, null));
    for (final var operation : workflowOperations) {
      final var exception = assertThrowsExactly(
          io.vanillabp.spi.process.WorkflowNotFoundException.class,
          operation);
      assertTrue(exception.getMessage().contains("4711"));
    }

    // a definition id not following '<adapter id>#<BPMS specific id>'
    final var definitionException = assertThrowsExactly(
        io.vanillabp.spi.process.ProcessDefinitionNotFoundException.class,
        () -> testee.getBpmnXml("definition-1"));
    assertTrue(definitionException.getMessage().contains("definition-1"));

  }

}
