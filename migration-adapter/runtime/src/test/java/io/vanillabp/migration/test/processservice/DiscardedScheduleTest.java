package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.RunningActivation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The outbox may refuse to plan an operation. An application must not have to discover
 * that by watching a workflow wait forever. The store answers <code>false</code> where an
 * identical operation is still waiting for its dispatch. This test pins what the core does
 * with that answer. It writes a WARN which names what was dropped and where it belongs. It
 * also names both causes, which nothing here can tell apart (see decision 22 in the
 * repository's DECISIONS.md).
 * <p>
 * It also pins which operations reach that answer at all. Multi-instance siblings of one
 * aggregate used to reach it. Three elements of a multi-instance call activity share
 * workflow module, BPMN process and aggregate id. So their correlations shared a key, and
 * two of the three were dropped. Now the activation which planned an operation is part of
 * the key, so they do not reach it any more (see decision 23 in the repository's
 * DECISIONS.md). What is left is a caller which repeats itself within ONE activation, or
 * outside any. Both cases are here, because the difference between them is the feature.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DiscardedScheduleTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  /**
   * What the counter of a refused schedule saw, in the order it was told. An alarm on
   * <code>vanillabp.outbox.discarded</code> finds a workflow which waits for a message that
   * nobody will send again. It finds it in hours instead of weeks. So the count and the WARN
   * have to move together.
   */
  private final java.util.List<String> discardedOperations = new java.util.ArrayList<>();

  private ListAppender<ILoggingEvent> logWatcher;

  private Logger serviceLogger;

  @BeforeEach
  public void watchTheLog() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    serviceLogger = (Logger) LoggerFactory.getLogger(MigrationProcessService.class);
    serviceLogger.addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    serviceLogger.detachAppender(logWatcher);
    logWatcher.stop();

  }

  /**
   * A store which behaves like the real ones. A key deduplicates while the entry which
   * carries it waits for its dispatch. Nothing here dispatches anything.
   */
  private static final class PendingKeyOutbox implements PhaseTwoOutbox {

    private final java.util.Set<String> pending = new java.util.LinkedHashSet<>();

    private final java.util.List<io.vanillabp.integration.spi.PhaseTwoCall> planned = new java.util.ArrayList<>();

    @Override
    public boolean schedule(
        final io.vanillabp.integration.spi.PhaseTwoCall call) {

      final var key = call.idempotencyKey();
      if (key.isPresent() && !pending.add(key.get())) {
        return false;
      }
      planned.add(call);
      return true;

    }

  }

  private MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * A process service which talks to a remote BPMS, so everything goes through the outbox.
   * Its store discards every schedule which carries a key.
   */
  private MigrationProcessService<Object> serviceWithDiscardingOutbox(
      final PhaseTwoOutbox outbox) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> adapter = mock(MigratableProcessService.class);
    io.vanillabp.migration.test.AdapterMocks.recordingItsOperations(adapter);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);
    lenient()
        .when(adapter.awarenessOfWorkflow(any(), any(), any()))
        .thenReturn(WorkflowAwareness.ACTIVE);
    // the election hands the workflow id to a probe about a workflow. A mock does not run
    // the default which drops it
    lenient()
        .when(adapter.awarenessOfWorkflow(any(), any(), any(), any()))
        .thenReturn(WorkflowAwareness.ACTIVE);

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    final var aggregate = new Object();
    lenient().when(persistence.save(any())).thenReturn(aggregate);
    lenient().when(persistence.getAggregateId(any())).thenReturn("4711");

    final PhaseTwoOutboxResolver resolver = new PhaseTwoOutboxResolver() {

      @Override
      public PhaseTwoOutbox resolveFor(
          final Class<?> workflowAggregateClass) {
        return outbox;
      }

      @Override
      public String remediesDescription() {
        return "- add a store, or";
      }

      @Override
      public java.util.Collection<PhaseTwoOutbox> allStores() {
        return java.util.List.of(outbox);
      }

    };

    final var service = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties())
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(resolver)
        .build();
    service
        .setMetrics(
            new io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics() {

              @Override
              public void outboxScheduleDiscarded(
                  final String operation) {

                discardedOperations.add(operation);

              }

            });
    return service;

  }

  private String theWarning() {

    final var warnings = logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
    assertEquals(1, warnings.size(), "expected exactly one warning but got: "
        + warnings);
    return warnings.getFirst();

  }

  @Test
  @DisplayName("A discarded correlation is reported, naming the message, the id and both causes")
  public void aDiscardedCorrelationIsReported() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(outbox.schedule(any()))
        .thenReturn(false);

    serviceWithDiscardingOutbox(outbox).correlateMessage(new Object(), "ItemShipped", "item-4711");

    final var warning = theWarning();
    assertTrue(warning.contains("ItemShipped"), warning);
    assertTrue(warning.contains("item-4711"), warning);
    assertTrue(warning.contains("4711"), warning);
    assertTrue(warning.contains(PROCESS), warning);
    assertTrue(warning.contains(MODULE), warning);
    // both causes, because the store cannot tell them apart. And the remedy on which the
    // application can act
    assertTrue(warning.contains("redelivery"), warning);
    assertTrue(warning.contains("second, legitimate operation"), warning);
    assertTrue(warning.contains("vary the correlation id"), warning);

  }

  @Test
  @DisplayName("A discarded start is reported the same way")
  public void aDiscardedStartIsReported() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(outbox.schedule(any()))
        .thenReturn(false);

    serviceWithDiscardingOutbox(outbox).startWorkflow(new Object());

    final var warning = theWarning();
    assertTrue(warning.contains("starting the workflow"), warning);
    assertTrue(warning.contains("4711"), warning);
    assertTrue(warning.contains(PROCESS), warning);

  }

  @Test
  @DisplayName("Every refused schedule is counted, named by the operation which was lost")
  public void aDiscardedScheduleIsCountedPerOperation() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(outbox.schedule(any()))
        .thenReturn(false);
    final var testee = serviceWithDiscardingOutbox(outbox);

    testee.correlateMessage(new Object(), "ItemShipped", "item-4711");

    assertEquals(
        List.of("CORRELATE_MESSAGE"),
        discardedOperations,
        "the operation is the tag, because a lost message and a workflow which never "
            + "started are different alarms");
    assertEquals(1, logWatcher.list.size(), "one WARN, one increment");

    testee.startWorkflow(new Object());

    assertEquals(List.of("CORRELATE_MESSAGE", "START_WORKFLOW"), discardedOperations);

  }

  @Test
  @DisplayName("A planned operation is not counted as discarded")
  public void aPlannedOperationIsNotCounted() {

    final var outbox = new PendingKeyOutbox();

    serviceWithDiscardingOutbox(outbox).correlateMessage(new Object(), "ItemShipped", "item-4711");

    assertTrue(discardedOperations.isEmpty(), "nothing was lost: "
        + discardedOperations);

  }

  @Test
  @DisplayName("A scheduled operation says nothing at all")
  public void aScheduledOperationIsSilent() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(outbox.schedule(any()))
        .thenReturn(true);

    serviceWithDiscardingOutbox(outbox).correlateMessage(new Object(), "ItemShipped", "item-4711");

    assertTrue(
        logWatcher.list
            .stream()
            .noneMatch(event -> event.getLevel() == Level.WARN),
        "a planned operation is not worth a word: "
            + logWatcher.list);

  }


  @Test
  @DisplayName("Multi-instance siblings of one aggregate each keep their correlation")
  public void siblingsOfOneAggregateAreToldApartByTheirActivation() {

    // three elements of a multi-instance call activity. The called process is a secondary
    // workflow of the SAME aggregate. So the three correlations share workflow module, BPMN
    // process and aggregate id. A correlation id read from business data does not have to
    // differ. What differs is the activation in which each of them was planned. The BPMS
    // names exactly that, and the idempotency key carries it
    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    for (final var element : List.of("element-1", "element-2", "element-3")) {
      try (var activation = RunningActivation.of(element)) {
        testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
      }
    }

    assertEquals(3, outbox.planned.size(), "all three siblings were planned");
    assertEquals(
        3,
        outbox.pending.size(),
        "three keys, one per activation: "
            + outbox.pending);
    assertTrue(
        logWatcher.list
            .stream()
            .noneMatch(event -> event.getLevel() == Level.WARN),
        "nothing was lost, so nothing is warned about: "
            + logWatcher.list);

  }

  @Test
  @DisplayName("A repeated delivery of ONE activation still correlates once")
  public void aRepeatedDeliveryOfOneActivationCorrelatesOnce() {

    // the guarantee which the activation identity must not cost. When the BPMS hands the
    // same element instance out twice, that is not a second element. Its correlation is the
    // one which is already waiting
    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    try (var first = RunningActivation.of("element-1")) {
      testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    }
    try (var redelivery = RunningActivation.of("element-1")) {
      testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    }

    assertEquals(1, outbox.planned.size(), "the redelivery found its own entry waiting");

  }

  @Test
  @DisplayName("Inside an activation the warning says what a sibling is not")
  public void aDiscardInsideAnActivationNamesTheActivation() {

    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    try (var activation = RunningActivation.of("element-1")) {
      testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
      testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    }

    final var warning = theWarning();
    assertTrue(warning.contains("element-1"), warning);
    // the remedy of the other branch would send the reader looking for a sibling, and this
    // cannot be one
    assertTrue(warning.contains("one activation asking twice"), warning);

  }

  @Test
  @DisplayName("Outside any activation a repetition is still discarded, audibly")
  public void aRepetitionOutsideAnyActivationIsDiscardedAndSaidSo() {

    // a REST endpoint which correlates the same message twice for one aggregate cannot be
    // told apart from a repeat of itself, and it stays that way. The activation in the key
    // fixed the siblings, not this
    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    testee.correlateMessage(new Object(), "OfferRequested", "partner-42");

    assertEquals(1, outbox.planned.size(), "the second one lost against the first");
    final var warning = theWarning();
    assertTrue(warning.contains("outside any activation"), warning);
    assertTrue(warning.contains("vary the correlation id"), warning);

  }

  @Test
  @DisplayName("A handler spawning its own thread loses the activation and the key with it")
  public void anActivationDoesNotReachAThreadTheHandlerStarted() throws Exception {

    // absent, instead of failing. The activation is a plain ThreadLocal and never an
    // inheritable one. So a pooled thread cannot carry an activation into work which does
    // not belong to it. The price: a handler which correlates from a thread of its own gets
    // the key of a correlation which knows no activation
    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    try (var activation = RunningActivation.of("element-1")) {
      testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
      final var spawned = new Thread(
          () -> testee.correlateMessage(new Object(), "OfferRequested", "partner-42"));
      spawned.start();
      spawned.join();
    }

    assertEquals(2, outbox.planned.size(), "the spawned thread planned a key of its own");
    assertTrue(
        outbox.pending.stream().anyMatch(key -> key.endsWith("partner-42")),
        "the key without an activation is the one this story does not change: "
            + outbox.pending);

  }

  @Test
  @DisplayName("A scope restores the activation it found instead of clearing it")
  public void aNestedScopeRestoresTheOuterActivation() {

    // an embedded engine can invoke a second handler within the execution of the first
    // one, and the outer activation has to survive that
    try (var outer = RunningActivation.of("element-1")) {
      try (var inner = RunningActivation.of("element-2")) {
        assertEquals("element-2", RunningActivation.current());
      }
      assertEquals("element-1", RunningActivation.current());
    }
    assertEquals(null, RunningActivation.current(), "nothing leaks out of the outermost scope");

  }

  @Test
  @DisplayName("A scope of an adapter reporting nothing hides the one around it")
  public void aScopeWithoutAnActivationHidesTheOuterOne() {

    // an adapter which cannot name its activations must not inherit the name of whatever
    // ran on this thread before
    try (var outer = RunningActivation.of("element-1")) {
      try (var silent = RunningActivation.of(null)) {
        assertEquals(null, RunningActivation.current());
      }
      assertEquals("element-1", RunningActivation.current());
    }

  }

}
