package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Broadcasting a BPMN signal. A signal is not addressed to a workflow, so
 * nothing is probed and no aggregate is touched - what matters is WHO gets it
 * (every BPMS the workflow module is deployed to) and WHEN (never inside the
 * caller's transaction: one outbox entry per BPMS, dispatched after the commit).
 */
@ExtendWith(SuppressOutputExtension.class)
public class SendSignalTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * Records what an adapter was asked to broadcast, and can be made to fail.
   */
  static class RecordingAdapter implements MigratableProcessService<Object> {

    private final String adapterId;

    final List<String> phaseOne = new LinkedList<>();

    final List<String> phaseTwo = new LinkedList<>();

    boolean fail = false;

    RecordingAdapter(
        final String adapterId) {
      this.adapterId = adapterId;
    }

    @Override
    public String getAdapterId() {
      return adapterId;
    }

    @Override
    public void sendSignalPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String signalName) {
      if (fail) {
        throw new IllegalStateException("BPMS '%s' is unreachable".formatted(adapterId));
      }
      phaseOne.add(signalName);
    }

    @Override
    public void sendSignalPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String signalName) {
      phaseTwo.add(signalName);
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final io.vanillabp.integration.spi.AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public void startWorkflowPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate) {
    }

    @Override
    public void startWorkflowPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
    }

    @Override
    public void completeTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId) {
    }

    @Override
    public void completeTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId) {
    }

    @Override
    public void cancelTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void cancelTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void completeUserTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId) {
    }

    @Override
    public void completeUserTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId) {
    }

    @Override
    public void cancelUserTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void cancelUserTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void correlateMessagePhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String messageName,
        final String correlationId) {
    }

    @Override
    public void correlateMessagePhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String messageName,
        final String correlationId) {
    }

    @Override
    public void startWorkflowByMessagePhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String messageName) {
    }

    @Override
    public void startWorkflowByMessagePhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String messageName) {
    }

  }

  static class RecordingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new LinkedList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {
      scheduled.add(call);
      return true;
    }

  }

  static class PersistenceStub implements AggregatePersistenceAware<Object> {

    @Override
    public Class<Object> getAggregateClass() {
      return Object.class;
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

  }

  /**
   * One workflow module with TWO adapters, but only the first one is prioritized
   * for this process - the second serves another workflow of the module, which is
   * exactly what the deployment union covers.
   */
  private MigrationAdapterProperties properties() {

    final var otherWorkflow = WorkflowAdapterProperties
        .builder()
        .prioritizedAdapters(List.of("second-adapter"))
        .build();
    final var workflowModule = WorkflowModuleAdapterProperties
        .builder()
        .workflows(Map.of("OtherProcess", otherWorkflow))
        .build();
    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(
            Map
                .of(
                    "first-adapter", AdapterConfigProperties.ofType("dummy"),
                    "second-adapter", AdapterConfigProperties.ofType("other")))
        .prioritizedAdapters(List.of("first-adapter"))
        .workflowModules(Map.of(MODULE, workflowModule))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Object> processService(
      final List<MigratableProcessService<Object>> adapters,
      final PhaseTwoOutbox outbox) {

    io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver resolver = null;
    if (outbox != null) {
      resolver = new io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver() {

        @Override
        public PhaseTwoOutbox resolveFor(
            final Class<?> workflowAggregateClass) {
          return outbox;
        }

        @Override
        public String remediesDescription() {
          return "test";
        }

      };
    }
    return new MigrationProcessService<Object>(
        MODULE, PROCESS, Object.class, properties(), new PersistenceStub(), adapters, resolver);

  }

  @Test
  @DisplayName("The broadcast reaches every BPMS the workflow module is deployed to")
  public void everyDeployedBpmsIsReached() {

    final var first = new RecordingAdapter("first-adapter");
    final var second = new RecordingAdapter("second-adapter");
    final var outbox = new RecordingOutbox();

    processService(List.of(first, second), outbox).sendSignal("OrderReceived");

    // the second adapter serves another workflow of the module, not this process -
    // a broadcast reaching only the elected BPMS would miss the workflows waiting
    // there
    assertEquals(List.of("OrderReceived"), first.phaseOne);
    assertEquals(List.of("OrderReceived"), second.phaseOne);
    assertEquals(2, outbox.scheduled.size());

  }

  @Test
  @DisplayName("Every BPMS gets an outbox entry carrying its adapter and no aggregate")
  public void everyBpmsBroadcastsAfterTheCommit() {

    final var first = new RecordingAdapter("first-adapter");
    final var second = new RecordingAdapter("second-adapter");
    final var outbox = new RecordingOutbox();

    processService(List.of(first, second), outbox).sendSignal("OrderReceived");

    // nothing was broadcast inside the caller's transaction
    assertTrue(first.phaseTwo.isEmpty());
    assertTrue(second.phaseTwo.isEmpty());

    assertEquals(2, outbox.scheduled.size());
    final var call = outbox.scheduled.getLast();
    assertEquals("SEND_SIGNAL", call.operation());
    assertEquals("second-adapter", call.adapterId());
    assertEquals("OrderReceived", call.args().get(PhaseTwoCall.ARG_SIGNAL_NAME));
    // a broadcast is not about one workflow
    assertEquals(null, call.workflowAggregateId());
    // and nothing about it can be deduplicated
    assertTrue(call.idempotencyKey().isEmpty());

  }

  @Test
  @DisplayName("Phase two broadcasts to the adapter the entry was written for")
  public void phaseTwoUsesTheRecordedAdapter() {

    final var first = new RecordingAdapter("first-adapter");
    final var second = new RecordingAdapter("second-adapter");

    processService(List.of(first, second), new RecordingOutbox())
        .sendSignalPhaseTwo("OrderReceived", "second-adapter");

    assertTrue(first.phaseTwo.isEmpty());
    assertEquals(List.of("OrderReceived"), second.phaseTwo);

  }

  @Test
  @DisplayName("An entry for an adapter which is gone fails guiding")
  public void phaseTwoForAnUnknownAdapterFailsGuiding() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> processService(List.of(new RecordingAdapter("first-adapter")), new RecordingOutbox())
            .sendSignalPhaseTwo("OrderReceived", "gone-adapter"));

    assertTrue(exception.getMessage().contains("gone-adapter"));
    assertTrue(exception.getMessage().contains("OrderReceived"));
    assertTrue(exception.getMessage().contains("outbox store"));

  }

  @Test
  @DisplayName("One unreachable BPMS does not stop the broadcast to the others")
  public void oneFailingBpmsDoesNotStopTheOthers() {

    final var failing = new RecordingAdapter("first-adapter");
    failing.fail = true;
    final var reachable = new RecordingAdapter("second-adapter");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> processService(List.of(failing, reachable), null).sendSignal("OrderReceived"));

    // the failure is reported, but only after every other BPMS was asked
    assertTrue(exception.getMessage().contains("first-adapter"));
    assertEquals(List.of("OrderReceived"), reachable.phaseOne);

  }

  @Test
  @DisplayName("An adapter whose BPMS has no signals says so, naming both ways out")
  public void anAdapterWithoutSignalsSaysSo() {

    // the SPI's own default, not a stub: an adapter which never implemented signals
    // inherits it, and its text is the only thing the developer gets
    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> signalless = Mockito
        .mock(MigratableProcessService.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    Mockito.lenient().doReturn("first-adapter").when(signalless).getAdapterId();

    final var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> processService(List.of(signalless), null).sendSignal("OrderReceived"));

    assertTrue(exception.getMessage().contains("first-adapter"), exception.getMessage());
    assertTrue(exception.getMessage().contains("OrderReceived"), exception.getMessage());
    assertTrue(exception.getMessage().contains(MODULE), exception.getMessage());
    assertTrue(exception.getMessage().contains("prioritized adapters"), exception.getMessage());
    assertTrue(exception.getMessage().contains("message"), exception.getMessage());

  }

  @Test
  @DisplayName("A blank signal name fails naming the process")
  public void blankSignalNameFails() {

    final var exception = assertThrows(
        IllegalArgumentException.class,
        () -> processService(List.of(new RecordingAdapter("first-adapter")), null).sendSignal("  "));

    assertTrue(exception.getMessage().contains(PROCESS));
    assertTrue(exception.getMessage().contains(MODULE));

  }

}
