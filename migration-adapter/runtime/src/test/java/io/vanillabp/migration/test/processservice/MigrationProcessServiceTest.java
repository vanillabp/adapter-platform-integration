package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.AggregatePersistenceAware;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;

@ExtendWith(MockitoExtension.class)
public class MigrationProcessServiceTest {

  @Mock
  private MigratableProcessService<Object> processService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistence;

  @Mock
  private PhaseTwoOutbox phaseTwoOutbox;

  /**
   * Creates properties with one configured and prioritized adapter 'test-adapter'.
   */
  private MigrationAdapterProperties createProperties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", "dummy"))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.setWorkflowModules(properties.getWorkflowModules());
    return properties;

  }

  @Test
  @DisplayName("Constructor fails if no process services are given at all")
  public void constructorFailsOnEmptyListOfProcessServices() {

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new MigrationProcessService<>(
            "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List.of(), null));

    // the exception has to name the workflow module, the BPMN process ID and the prioritized adapters
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-adapter"));

  }

  @Test
  @DisplayName("Constructor fails if no process service matches any prioritized adapter")
  public void constructorFailsIfNoProcessServiceMatches() {

    when(processService.getAdapterId()).thenReturn("other-adapter");

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new MigrationProcessService<>(
            "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
                .of(processService), null));

    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-adapter"));

  }

  @Test
  @DisplayName("startWorkflow passes the attached aggregate to phase one")
  public void startWorkflowPassesAttachedAggregateToPhaseOne() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), null);

    // with JPA and generated IDs the attached aggregate returned by save may
    // be another object than the detached one passed to startWorkflow
    final var detachedAggregate = new Object();
    final var attachedAggregate = new Object();
    when(aggregatePersistence.save(detachedAggregate)).thenReturn(attachedAggregate);
    when(aggregatePersistence.getAggregateId(attachedAggregate)).thenReturn(42L);

    final var result = testee.startWorkflow(detachedAggregate);

    assertSame(attachedAggregate, result);
    verify(processService).startWorkflowPhaseOne(aggregatePersistence, attachedAggregate);
    verify(processService, never()).startWorkflowPhaseOne(aggregatePersistence, detachedAggregate);

  }

  @Test
  @DisplayName("startWorkflow schedules phase two via the outbox if the adapter requires a two-phase commit")
  public void startWorkflowSchedulesPhaseTwoIfTwoPhaseCommitIsRequired() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutbox);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);

    testee.startWorkflow(aggregate);

    // the outbox entry has to be scheduled using the idempotency key
    // 'workflowModuleId + bpmnProcessId + workflowAggregateId' and the adapter's ID
    verify(phaseTwoOutbox).schedule("test-module", "TestProcess", "test-adapter", 42L);

  }

  @Test
  @DisplayName("startWorkflow does not schedule phase two if the adapter does not require a two-phase commit")
  public void startWorkflowDoesNotSchedulePhaseTwoIfNoTwoPhaseCommitIsRequired() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutbox);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);

    testee.startWorkflow(aggregate);

    verify(phaseTwoOutbox, never()).schedule(any(), any(), any(), any());

  }

  @Test
  @DisplayName("startWorkflow fails if a two-phase commit is required but no outbox is available")
  public void startWorkflowFailsIfTwoPhaseCommitIsRequiredButOutboxIsMissing() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), null);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflow(aggregate));

    // the exception has to name the adapter, the BPMN process ID, the workflow module
    // and the missing SPI interface
    assertTrue(exception.getMessage().contains("test-adapter"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("PhaseTwoOutbox"));

  }

  @Test
  @DisplayName("needsTransactionForStartingWorkflows delegates to the first prioritized adapter")
  public void needsTransactionForStartingWorkflowsDelegates() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true, false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), null);

    assertTrue(testee.needsTransactionForStartingWorkflows());
    assertFalse(testee.needsTransactionForStartingWorkflows());

  }

}
