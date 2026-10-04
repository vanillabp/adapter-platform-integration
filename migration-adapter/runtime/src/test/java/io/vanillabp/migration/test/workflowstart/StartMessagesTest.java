package io.vanillabp.migration.test.workflowstart;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowstart.StartMessages;
import io.vanillabp.integration.adapter.migration.workflowtask.DeclaredBpmnProcesses;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The check of <code>ProcessService#startWorkflowByMessage</code> against the messages the
 * adapters reported as starting a process: what is refused, what passes, which adapter is
 * asked while two of them serve the process, and what the start says where nothing was
 * reported.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StartMessagesTest {

  private static final String MODULE = "order-module";

  private static final String PROCESS = "OrderProcess";

  /**
   * The sentence the start writes for a process whose adapter reported nothing. The case
   * which has to say it and the case which must not both quote it.
   */
  private static final String NOT_CHECKED = "so ProcessService#startWorkflowByMessage is not checked for it";

  /** Stands for whatever the save of an accepted start would do. */
  private static class SaveReached extends RuntimeException {

    private static final long serialVersionUID = 1L;

  }

  @Test
  @DisplayName("A message the model of the starting adapter names passes the check")
  public void aReportedMessagePasses() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced", "OrderImported"));

    assertDoesNotThrow(
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "OrderImported"));

  }

  @Test
  @DisplayName("A message the model does not name is refused, and the refusal names the messages which do")
  public void anUnknownMessageIsRefused() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced", "OrderImported"));

    final var refusal = assertThrows(
        IllegalArgumentException.class,
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "InvoicePaid"));

    assertEquals(
        "Message 'InvoicePaid' does not start BPMN process 'OrderProcess' of workflow module "
            + "'order-module'! ProcessService#startWorkflowByMessage starts the process of its own "
            + "ProcessService and no other. The messages which start this process in the model adapter "
            + "'c7' deployed are: 'OrderImported', 'OrderPlaced'. Pass one of them, or use the "
            + "ProcessService of the process the message is meant to start. VanillaBP 1 started any "
            + "process which knew the message.",
        refusal.getMessage());

  }

  @Test
  @DisplayName("A process without a message start event refuses every message and points to startWorkflow")
  public void aProcessWithoutMessageStartRefusesEveryMessage() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of());

    final var refusal = assertThrows(
        IllegalArgumentException.class,
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "OrderPlaced"));

    assertTrue(
        refusal
            .getMessage()
            .contains("This process has no message start event in the model adapter 'c7' deployed. Start it "
                + "with ProcessService#startWorkflow"),
        refusal.getMessage());

  }

  @Test
  @DisplayName("A message is compared by its plain name, the one the application passes")
  public void theNameIsComparedPlain() {

    // the adapter strips its prefix before it reports, so the scoped form is a
    // different name to the check, exactly as it is to the adapter which scopes what
    // the application passes
    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced"));

    assertThrows(
        IllegalArgumentException.class,
        () -> startMessages
            .refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, MODULE
                + "__OrderPlaced"));

  }

  @Test
  @DisplayName("Nothing reported means nothing checked")
  public void nothingReportedNothingChecked() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c8", MODULE, PROCESS, List.of("OrderPlaced"));

    // another adapter, another process: neither reported anything
    assertDoesNotThrow(
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "InvoicePaid"));
    assertDoesNotThrow(
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c8", MODULE, "OtherProcess", "InvoicePaid"));

  }

  @Test
  @DisplayName("Two reports of one adapter for one process add up")
  public void twoReportsAddUp() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced"));
    startMessages.report("c7", MODULE, PROCESS, null);
    startMessages.report("c7", MODULE, PROCESS, java.util.Arrays.asList("OrderImported", null));

    assertDoesNotThrow(
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "OrderPlaced"));
    assertDoesNotThrow(
        () -> startMessages.refuseAMessageWhichStartsAnotherProcess("c7", MODULE, PROCESS, "OrderImported"));

  }

  @Test
  @DisplayName("During a migration the adapter which starts the workflow is asked, not the other one")
  public void theStartingAdapterIsAsked() {

    final var startMessages = new StartMessages(null);
    // the new BPMS got a model where the message was renamed, the old one still has the old name
    startMessages.report("c8", MODULE, PROCESS, List.of("OrderReceived"));
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced"));

    final var processService = processServiceOf(List.of("c8", "c7"));
    processService.checkStartMessagesAgainst(startMessages);

    final var refusal = assertThrows(
        IllegalArgumentException.class,
        () -> processService.startWorkflowByMessage(new Object(), "OrderPlaced"));
    assertTrue(refusal.getMessage().contains("adapter 'c8'"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("'OrderReceived'"), refusal.getMessage());

    // the accepted one walks on to the save, which this test stops there
    assertThrows(
        SaveReached.class,
        () -> processService.startWorkflowByMessage(new Object(), "OrderReceived"));

  }

  @Test
  @DisplayName("A refused message is refused before the aggregate is saved")
  public void theRefusalComesBeforeTheSave() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced"));
    final var persistence = persistence();
    final var processService = processServiceOf(List.of("c7"), persistence);
    processService.checkStartMessagesAgainst(startMessages);

    assertThrows(
        IllegalArgumentException.class,
        () -> processService.startWorkflowByMessage(new Object(), "InvoicePaid"));
    verify(persistence, never()).save(any());

  }

  @Test
  @DisplayName("A process service nobody handed the reports checks nothing")
  public void aProcessServiceWithoutReportsChecksNothing() {

    final var processService = processServiceOf(List.of("c7"));

    assertThrows(
        SaveReached.class,
        () -> processService.startWorkflowByMessage(new Object(), "InvoicePaid"));
    // and says nothing at the start either
    assertTrue(whatTheStartSays(processService::sayWhereStartMessagesAreNotCheckedAfterDeployment).isEmpty());

  }

  @Test
  @DisplayName("The start says once per process that it is not checked where the starting adapter reported nothing")
  public void theStartSaysWhereNothingIsChecked() {

    final var startMessages = new StartMessages(null);
    // only the adapter which does NOT start the workflow reported
    startMessages.report("c7", MODULE, PROCESS, List.of("OrderPlaced"));
    final var processService = processServiceOf(List.of("pea", "c7"));
    processService.checkStartMessagesAgainst(startMessages);

    final var said = whatTheStartSays(processService::sayWhereStartMessagesAreNotCheckedAfterDeployment);

    assertEquals(1, said.size(), said.toString());
    assertEquals(
        "Adapter 'pea' does not say which messages start BPMN process 'OrderProcess' of workflow module "
            + "'order-module', "
            + NOT_CHECKED
            + ". A message which starts another process starts "
            + "that one, as in VanillaBP 1.",
        said.getFirst());

  }

  @Test
  @DisplayName("The start says nothing where the starting adapter reported, or where no model was deployed")
  public void theStartSaysNothingWhereThereIsNothingToSay() {

    final var startMessages = new StartMessages(null);
    startMessages.report("c7", MODULE, PROCESS, List.of());
    final var reported = processServiceOf(List.of("c7"));
    reported.checkStartMessagesAgainst(startMessages);

    final var declaredOnly = new StartMessages(new DeclaredBpmnProcesses() {

      @Override
      public boolean isDeclaredWithoutDeployment(
          final String workflowModuleId,
          final String bpmnProcessId) {
        return true;
      }

      @Override
      public Collection<String> deployedProcessesOf(
          final String workflowModuleId) {
        return List.of();
      }

    });
    final var renamed = processServiceOf(List.of("c7"));
    renamed.checkStartMessagesAgainst(declaredOnly);

    final var said = whatTheStartSays(() -> {
      reported.sayWhereStartMessagesAreNotCheckedAfterDeployment();
      renamed.sayWhereStartMessagesAreNotCheckedAfterDeployment();
    });

    assertFalse(said.stream().anyMatch(line -> line.contains(NOT_CHECKED)), said.toString());

  }

  private static List<String> whatTheStartSays(
      final Runnable start) {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var logger = (Logger) LoggerFactory.getLogger(StartMessages.class);
    logger.addAppender(logWatcher);
    try {
      start.run();
    } finally {
      logger.detachAppender(logWatcher);
    }
    return logWatcher.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @SuppressWarnings("unchecked")
  private static AggregatePersistenceAware<Object> persistence() {

    final var persistence = (AggregatePersistenceAware<Object>) mock(AggregatePersistenceAware.class);
    when(persistence.getAggregateClass()).thenReturn(Object.class);
    org.mockito.Mockito.doReturn(String.class).when(persistence).getAggregateIdType();
    when(persistence.save(any())).thenThrow(new SaveReached());
    return persistence;

  }

  private static MigrationProcessService<Object> processServiceOf(
      final List<String> prioritizedAdapters) {

    return processServiceOf(prioritizedAdapters, persistence());

  }

  @SuppressWarnings("unchecked")
  private static MigrationProcessService<Object> processServiceOf(
      final List<String> prioritizedAdapters,
      final AggregatePersistenceAware<Object> persistence) {

    final var adapters = new java.util.LinkedHashMap<String, AdapterConfigProperties>();
    prioritizedAdapters.forEach(adapterId -> adapters.put(adapterId, AdapterConfigProperties.ofType("dummy")));
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.copyOf(adapters))
        .prioritizedAdapters(prioritizedAdapters)
        .build();
    properties.validateAndLink();
    final var processServices = prioritizedAdapters
        .stream()
        .map(adapterId -> {
          final var adapter = (MigratableProcessService<Object>) mock(MigratableProcessService.class);
          when(adapter.getAdapterId()).thenReturn(adapterId);
          return adapter;
        })
        .toList();
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(processServices)
        .build();

  }

}
