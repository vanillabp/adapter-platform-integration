package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An operation the outbox refuses to plan is the one thing an application must not have
 * to discover by watching a workflow wait forever. The store answers
 * <code>false</code> where an identical operation is still waiting for its dispatch, and
 * this pins what the core does with that answer: a WARN naming what was dropped, where
 * it belongs and both causes nothing here can tell apart (see decision 22 in the
 * repository's DECISIONS.md).
 * <p>
 * Until the identity of an activation reaches the outbound side, that warning is the
 * only signal an application gets when two multi-instance siblings of one aggregate
 * correlate the same message with the same correlation id. Asserting a log level looks
 * petty; this line is the feature.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DiscardedScheduleTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

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
   * A store behaving like the real ones: a key deduplicates while the entry carrying it
   * waits for its dispatch, and nothing here dispatches anything.
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
   * A process service talking to a remote BPMS (so everything goes through the outbox)
   * whose store discards every schedule carrying a key.
   */
  private MigrationProcessService<Object> serviceWithDiscardingOutbox(
      final PhaseTwoOutbox outbox) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(adapter.needsTwoPhaseCommitForStartingWorkflows()).thenReturn(true);
    lenient()
        .when(adapter.awarenessOfWorkflow(any(), any(), any()))
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

    };

    return new MigrationProcessService<>(
        MODULE, PROCESS, Object.class, properties(), persistence, List.of(adapter), resolver);

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
    when(
        outbox
            .scheduleCorrelateMessage(
                eq(MODULE), eq(PROCESS), any(), eq("ItemShipped"), eq("item-4711")))
        .thenReturn(false);

    serviceWithDiscardingOutbox(outbox).correlateMessage(new Object(), "ItemShipped", "item-4711");

    final var warning = theWarning();
    assertTrue(warning.contains("ItemShipped"), warning);
    assertTrue(warning.contains("item-4711"), warning);
    assertTrue(warning.contains("4711"), warning);
    assertTrue(warning.contains(PROCESS), warning);
    assertTrue(warning.contains(MODULE), warning);
    // both causes, because the store cannot tell them apart - and the remedy the
    // application can act on
    assertTrue(warning.contains("redelivery"), warning);
    assertTrue(warning.contains("second, legitimate operation"), warning);
    assertTrue(warning.contains("vary the correlation id"), warning);

  }

  @Test
  @DisplayName("A discarded start is reported the same way")
  public void aDiscardedStartIsReported() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(outbox.scheduleStartWorkflow(eq(MODULE), eq(PROCESS), any(), anyString()))
        .thenReturn(false);

    serviceWithDiscardingOutbox(outbox).startWorkflow(new Object());

    final var warning = theWarning();
    assertTrue(warning.contains("starting the workflow"), warning);
    assertTrue(warning.contains("4711"), warning);
    assertTrue(warning.contains(PROCESS), warning);

  }

  @Test
  @DisplayName("A scheduled operation says nothing at all")
  public void aScheduledOperationIsSilent() {

    final var outbox = mock(PhaseTwoOutbox.class);
    when(
        outbox
            .scheduleCorrelateMessage(
                eq(MODULE), eq(PROCESS), any(), eq("ItemShipped"), eq("item-4711")))
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
  @DisplayName("Multi-instance siblings of one aggregate lose their correlation, audibly")
  public void siblingsOfOneAggregateAreDiscardedAndSaidSo() {

    // three elements of a multi-instance call activity: the called process is a
    // secondary workflow of the SAME aggregate, so the three correlations share
    // workflow module, BPMN process and aggregate id, and a correlation id read from
    // business data does not have to differ. All three are planned in one batch of
    // work, so the narrowed window does not reach them - the first one wins and the
    // other two are lost. That is pinned here as it BEHAVES: telling a sibling from a
    // redelivery needs the identity of the activation, which the outbound side does not
    // report yet, and until it does the warning is all an application gets
    final var outbox = new PendingKeyOutbox();
    final var testee = serviceWithDiscardingOutbox(outbox);

    testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    testee.correlateMessage(new Object(), "OfferRequested", "partner-42");
    testee.correlateMessage(new Object(), "OfferRequested", "partner-42");

    assertEquals(1, outbox.planned.size(), "one of the three siblings was planned");
    assertEquals(
        2,
        logWatcher.list
            .stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .count(),
        "the two lost correlations are named: "
            + logWatcher.list);

  }

}
