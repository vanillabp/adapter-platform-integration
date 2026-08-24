package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class MigrationProcessServiceTest {

  /**
   * What a probe is asked about. Any scope does here: the adapters of this
   * test answer from what the test told them, not from a deployment.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

  @Mock
  private MigratableProcessService<Object> processService;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistence;

  @Mock
  private PhaseTwoOutbox phaseTwoOutbox;

  @Mock
  private PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * Creates properties with one configured and prioritized adapter 'test-adapter'.
   */
  private MigrationAdapterProperties createProperties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();
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

  /**
   * Creates properties with two configured and prioritized adapters
   * 'first-adapter' and 'second-adapter' (in this priority order).
   */
  private MigrationAdapterProperties createTwoAdapterProperties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(
            "first-adapter", AdapterConfigProperties.ofType("dummy"), "second-adapter", AdapterConfigProperties
                .ofType("other")))
        .prioritizedAdapters(List.of("first-adapter", "second-adapter"))
        .build();
    properties.validateAndLink();
    return properties;

  }

  @Test
  @DisplayName("Constructor fails fast if ANY prioritized adapter has no process service (B2)")
  public void constructorFailsFastOnAnyMissingPrioritizedAdapter() {

    // only the SECOND prioritized adapter is served - before the fix the missing
    // one was silently dropped and workflows started in the wrong BPMS
    when(processService.getAdapterId()).thenReturn("second-adapter");

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new MigrationProcessService<>(
            "test-module", "TestProcess", Object.class, createTwoAdapterProperties(), aggregatePersistence, List
                .of(processService), null));

    // the guiding message has to name the adapter id, module, process and the
    // likely causes (missing dependency, typo in the prioritized-adapters keys)
    assertTrue(exception.getMessage().contains("first-adapter"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("classpath"));
    assertTrue(exception.getMessage().contains("vanillabp.prioritized-adapters"));
    assertTrue(exception.getMessage().contains("vanillabp.workflow-modules.test-module.prioritized-adapters"));

  }

  @Test
  @DisplayName("Election order follows the configured priorities, not the bean registration order")
  public void electionOrderFollowsConfiguredPriorities() {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> secondAdapter = org.mockito.Mockito.mock(MigratableProcessService.class);
    when(processService.getAdapterId()).thenReturn("first-adapter");
    when(secondAdapter.getAdapterId()).thenReturn("second-adapter");

    // beans are passed in REVERSE priority order - the election must follow the
    // configured priorities nevertheless
    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createTwoAdapterProperties(), aggregatePersistence, List
            .of(secondAdapter, processService), phaseTwoOutboxResolver);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    testee.startWorkflow(aggregate);

    // phase one has to run in the highest-priority adapter only
    verify(processService).startWorkflowPhaseOne("test-module", "TestProcess", aggregatePersistence, aggregate);
    verify(secondAdapter, never()).startWorkflowPhaseOne(any(), any(), any(), any());

  }

  @Test
  @DisplayName("startWorkflow fails fast on a null or blank aggregate ID (checked once, in core)")
  public void startWorkflowFailsOnNullOrBlankAggregateId() {

    when(processService.getAdapterId()).thenReturn("test-adapter");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), null);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);

    // null ID
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(null);
    final var nullException = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflow(aggregate));
    assertTrue(nullException.getMessage().contains(Object.class.getName()));
    assertTrue(nullException.getMessage().contains("startWorkflow"));

    // blank ID
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("  ");
    assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflow(aggregate));

    // the adapter is never reached
    verify(processService, never()).startWorkflowPhaseOne(any(), any(), any(), any());

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
    verify(processService).startWorkflowPhaseOne("test-module", "TestProcess", aggregatePersistence, attachedAggregate);
    verify(processService, never())
        .startWorkflowPhaseOne("test-module", "TestProcess", aggregatePersistence, detachedAggregate);

  }

  @Test
  @DisplayName("startWorkflow schedules phase two via the outbox if the adapter requires a two-phase commit")
  public void startWorkflowSchedulesPhaseTwoIfTwoPhaseCommitIsRequired() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);
    when(phaseTwoOutboxResolver.resolveFor(Object.class)).thenReturn(phaseTwoOutbox);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);

    testee.startWorkflow(aggregate);

    // the adapter elected in phase one is part of the scheduled call: phase two
    // uses exactly this adapter instead of re-electing one from the then-current
    // priorities
    verify(phaseTwoOutbox).scheduleStartWorkflow("test-module", "TestProcess", 42L, "test-adapter");

  }

  @Test
  @DisplayName("startWorkflowPhaseTwo uses the adapter persisted with the outbox entry")
  public void startWorkflowPhaseTwoUsesPersistedAdapter() {

    when(processService.getAdapterId()).thenReturn("test-adapter");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.startWorkflowPhaseTwo(42L, "test-adapter");

    verify(processService).startWorkflowPhaseTwo("test-module", "TestProcess", aggregatePersistence, 42L);

  }

  @Test
  @DisplayName("startWorkflowPhaseTwo fails with a guiding message if the persisted adapter is no longer configured")
  public void startWorkflowPhaseTwoFailsOnStaleAdapter() {

    when(processService.getAdapterId()).thenReturn("test-adapter");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflowPhaseTwo(42L, "removed-adapter"));

    // the message has to name the stale adapter, the BPMN process, the workflow
    // module and that the entry is stale after a configuration change
    assertTrue(exception.getMessage().contains("removed-adapter"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("configuration"));
    verify(processService, never()).startWorkflowPhaseTwo(any(), any(), any(), any());

  }

  @Test
  @DisplayName("Re-dispatch mitigation: a previously attempted start entry probes the adapter and skips if the workflow is known")
  public void redispatchedStartSkipsIfWorkflowIsKnown() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.awarenessOfWorkflowForRedispatch(SCOPE, aggregatePersistence, 42L))
        .thenReturn(io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.startWorkflowPhaseTwo(42L, "test-adapter", true);

    // the previous dispatch already started the workflow - no second start
    verify(processService, never()).startWorkflowPhaseTwo(any(), any(), any(), any());

  }

  @Test
  @DisplayName("Re-dispatch mitigation: an unknown workflow proceeds with the (idempotent) start")
  public void redispatchedStartProceedsIfWorkflowIsUnknown() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.awarenessOfWorkflowForRedispatch(SCOPE, aggregatePersistence, 42L))
        .thenReturn(io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.startWorkflowPhaseTwo(42L, "test-adapter", true);

    verify(processService).startWorkflowPhaseTwo("test-module", "TestProcess", aggregatePersistence, 42L);

  }

  @Test
  @DisplayName("Re-dispatch mitigation: an unavailable BPMS fails the dispatch - the outbox entry stays pending")
  public void redispatchedStartFailsIfBpmsIsUnavailable() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.awarenessOfWorkflowForRedispatch(SCOPE, aggregatePersistence, 42L))
        .thenReturn(io.vanillabp.integration.adapter.spi.WorkflowAwareness.BPMS_UNAVAILABLE);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> testee.startWorkflowPhaseTwo(42L, "test-adapter", true));

    assertTrue(exception.getMessage().contains("test-adapter"));
    assertTrue(exception.getMessage().contains("retried"));
    verify(processService, never()).startWorkflowPhaseTwo(any(), any(), any(), any());

  }

  @Test
  @DisplayName("A first-time start dispatch never probes - the mitigation is for re-dispatches only")
  public void firstStartDispatchNeverProbes() {

    when(processService.getAdapterId()).thenReturn("test-adapter");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.startWorkflowPhaseTwo(42L, "test-adapter", false);

    verify(processService, never()).awarenessOfWorkflowForRedispatch(any(), any(), any());
    verify(processService).startWorkflowPhaseTwo("test-module", "TestProcess", aggregatePersistence, 42L);

  }

  @Test
  @DisplayName("Re-dispatch mitigation applies to starting by message, too")
  public void redispatchedStartByMessageSkipsIfWorkflowIsKnown() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.awarenessOfWorkflowForRedispatch(SCOPE, aggregatePersistence, 42L))
        .thenReturn(io.vanillabp.integration.adapter.spi.WorkflowAwareness.COMPLETED);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.startWorkflowByMessagePhaseTwo(42L, "test-message", "test-adapter", true);

    verify(processService, never()).startWorkflowByMessagePhaseTwo(any(), any(), any(), any(), any());

  }

  @Test
  @DisplayName("startWorkflow does not schedule phase two if the adapter does not require a two-phase commit")
  public void startWorkflowDoesNotSchedulePhaseTwoIfNoTwoPhaseCommitIsRequired() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);

    testee.startWorkflow(aggregate);

    verify(phaseTwoOutbox, never()).scheduleStartWorkflow(any(), any(), any(), any());

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
  @DisplayName("Startup validation fails with remedies if a two-phase commit is required but no outbox resolves")
  public void startupValidationFailsIfOutboxRequiredButNotResolvable() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);
    when(phaseTwoOutboxResolver.resolveFor(Object.class)).thenReturn(null);
    when(phaseTwoOutboxResolver.remediesDescription()).thenReturn("- add the platform's outbox starter, or");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        testee::validatePhaseTwoOutboxAtStartup);

    // the message names the adapter, process, module, aggregate, the platform's
    // remedies and the SPI escape hatches
    assertTrue(exception.getMessage().contains("test-adapter"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains(Object.class.getName()));
    assertTrue(exception.getMessage().contains("add the platform's outbox starter"));
    assertTrue(exception.getMessage().contains("PhaseTwoOutbox"));
    assertTrue(exception.getMessage().contains("PhaseTwoOutboxAware"));
    assertFalse(exception.getMessage().contains("  "), "message must not contain consecutive spaces");

  }

  @Test
  @DisplayName("Startup validation resolves nothing if no two-phase commit is required")
  public void startupValidationResolvesNothingWithoutTwoPhaseCommit() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.validatePhaseTwoOutboxAtStartup();

    // nothing materializes - an application using only embedded BPMS must not be
    // forced to have an outbox store
    verify(phaseTwoOutboxResolver, never()).resolveFor(any());

  }

  @Test
  @DisplayName("The outbox resolved at startup is reused when starting workflows")
  public void outboxResolvedAtStartupIsReused() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);
    when(phaseTwoOutboxResolver.resolveFor(Object.class)).thenReturn(phaseTwoOutbox);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    testee.validatePhaseTwoOutboxAtStartup();

    final var aggregate = new Object();
    when(aggregatePersistence.save(aggregate)).thenReturn(aggregate);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn(42L);
    testee.startWorkflow(aggregate);

    verify(phaseTwoOutbox).scheduleStartWorkflow("test-module", "TestProcess", 42L, "test-adapter");
    // resolved exactly once (at startup), not per workflow start
    verify(phaseTwoOutboxResolver).resolveFor(Object.class);

  }

  @Test
  @DisplayName("An unconvertible aggregate-ID type fails at construction with a guiding message")
  public void unconvertibleAggregateIdTypeFailsAtConstruction() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(aggregatePersistence.getAggregateIdType()).thenAnswer(invocation -> java.io.InputStream.class);

    final var exception = assertThrowsExactly(
        IllegalStateException.class,
        () -> new MigrationProcessService<>(
            "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
                .of(processService), phaseTwoOutboxResolver));

    assertTrue(exception.getMessage().contains(Object.class.getName()));
    assertTrue(exception.getMessage().contains("round-trip losslessly"));

  }

  @Test
  @DisplayName("The serialized aggregate ID is converted using the persistence's ID type")
  public void convertAggregateIdUsesPersistenceIdType() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(aggregatePersistence.getAggregateIdType()).thenAnswer(invocation -> Long.class);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    assertEquals(42L, testee.convertAggregateId("42"));

  }

  @Test
  @DisplayName("Without a determinable ID type the serialized aggregate ID is passed through")
  public void convertAggregateIdPassesThroughWithoutIdType() {

    when(processService.getAdapterId()).thenReturn("test-adapter");

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    assertEquals("custom-id-4711", testee.convertAggregateId("custom-id-4711"));

  }

  @Test
  @DisplayName("needsTwoPhaseCommitForStartingWorkflows delegates to the first prioritized adapter")
  public void needsTwoPhaseCommitForStartingWorkflowsDelegates() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    when(processService.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true, false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), null);

    assertTrue(testee.needsTwoPhaseCommitForStartingWorkflows());
    assertFalse(testee.needsTwoPhaseCommitForStartingWorkflows());

  }


  @Test
  @DisplayName("A phase-two failure the adapter calls permanent is marked as such")
  public void permanentPhaseTwoFailuresAreMarked() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    final var rejected = new IllegalArgumentException("the engine rejects this request");
    org.mockito.Mockito
        .doThrow(rejected)
        .when(processService)
        .startWorkflowPhaseTwo(
            org.mockito.ArgumentMatchers.eq("test-module"),
            org.mockito.ArgumentMatchers.eq("TestProcess"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(42L));
    when(processService.isPhaseTwoFailureRepeatable(rejected)).thenReturn(false);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    final var failure = assertThrowsExactly(
        io.vanillabp.integration.spi.PhaseTwoPermanentFailure.class,
        () -> testee.startWorkflowPhaseTwo(42L, "test-adapter", false));

    assertEquals(rejected, failure.getCause());
    // the store blocks the entry on this, so the message has to say why it will not
    // be retried
    assertTrue(failure.getMessage().contains("test-adapter"), failure.getMessage());
    assertTrue(failure.getMessage().contains("repeating it cannot help"), failure.getMessage());

  }

  @Test
  @DisplayName("A phase-two failure worth repeating travels unchanged - the store retries it")
  public void repeatablePhaseTwoFailuresTravelUnchanged() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    final var conflict = new IllegalStateException("another transaction touched the same row");
    org.mockito.Mockito
        .doThrow(conflict)
        .when(processService)
        .startWorkflowPhaseTwo(
            org.mockito.ArgumentMatchers.eq("test-module"),
            org.mockito.ArgumentMatchers.eq("TestProcess"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(42L));
    when(processService.isPhaseTwoFailureRepeatable(conflict)).thenReturn(true);

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver);

    assertEquals(
        conflict,
        assertThrowsExactly(
            IllegalStateException.class,
            () -> testee.startWorkflowPhaseTwo(42L, "test-adapter", false)));

  }

  @Test
  @DisplayName("Phase two of a start records which adapter holds the workflow - no probing needed")
  public void phaseTwoOfAStartFillsTheAdapterCache() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    final var cache = new io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache();

    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver, cache);
    testee.startWorkflowPhaseTwo(42L, "test-adapter", false);

    // the next operation on that workflow probes this adapter first, which is what
    // lets it wait out an eventually consistent BPMS instead of failing
    assertEquals(
        "test-adapter", cache.get("test-module", "TestProcess", "42").orElseThrow());

  }

  @Test
  @DisplayName("An inbound delivery records which adapter holds the workflow")
  public void anInboundDeliveryFillsTheAdapterCache() {

    when(processService.getAdapterId()).thenReturn("test-adapter");
    final var cache = new io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache();
    final var testee = new MigrationProcessService<>(
        "test-module", "TestProcess", Object.class, createProperties(), aggregatePersistence, List
            .of(processService), phaseTwoOutboxResolver, cache);

    // a handler which does not subscribe to the delivered event: the delivery still
    // proves where the workflow lives, which is why the recording happens first
    final var handler = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler.class);
    when(handler.acceptsEvent(any())).thenReturn(false);
    testee.executeWorkflowTask(
        handler, new io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext() {

          @Override
          public String getAdapterId() {
            return "test-adapter";
          }

          @Override
          public String getTaskDefinition() {
            return "someTask";
          }

          @Override
          public String getWorkflowAggregateId() {
            return "42";
          }

          @Override
          public io.vanillabp.spi.service.TaskEvent.Event getTaskEvent() {
            return io.vanillabp.spi.service.TaskEvent.Event.CANCELED;
          }

        }, null, List.of());

    assertEquals(
        "test-adapter", cache.get("test-module", "TestProcess", "42").orElseThrow());

  }


}
