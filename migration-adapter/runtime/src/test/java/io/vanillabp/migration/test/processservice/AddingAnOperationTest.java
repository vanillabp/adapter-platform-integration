package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseOperationNotSupported;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.Election;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What story 160 promises: adding an operation costs ONE place in the core and ONE in
 * an adapter, and nothing else. This test is the promise - it adds an operation the way
 * a new core operation would be added (the constant below stands in for a constant in
 * {@link PhaseOperation}), lets an adapter contribute a handler for it, and runs it
 * through both phases without touching the outbox SPI, the router, the process service
 * or a pair of methods per operation.
 * <p>
 * The adapter here is written the way an adapter is written now: a map of handlers and
 * the three probes, no phase methods at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AddingAnOperationTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ARG_ARCHIVE = "archive";

  /**
   * The whole definition of a new operation: its persisted name, what deduplicates it,
   * which BPMS serves it and how it names itself in a message.
   */
  private static final PhaseOperation ARCHIVE_WORKFLOW = PhaseOperation
      .extensionOperation("test:ARCHIVE_WORKFLOW")
      .electedBy(Election.HOLDS_THE_WORKFLOW)
      .idempotencyKey(
          call -> Optional
              .of(
                  "test:ARCHIVE_WORKFLOW|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId())))
      .describedAs(args -> "archiving into '%s'".formatted(args.get(ARG_ARCHIVE)))
      .remedyWhenUnsupported("Use an adapter whose BPMS keeps an archive.")
      .build();

  /**
   * An adapter in the shape story 160 introduced: it answers the probes and contributes
   * one handler per operation it serves. Whether it serves the new operation is one
   * entry in that map - which is the second and last place adding an operation touches.
   */
  private static class ArchivingAdapter implements MigratableProcessService<Object> {

    final List<String> phaseOne = new LinkedList<>();

    final List<String> phaseTwo = new LinkedList<>();

    private final boolean archives;

    ArchivingAdapter(
        final boolean archives) {
      this.archives = archives;
    }

    @Override
    public String getAdapterId() {
      return "archiving-adapter";
    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Object>>();
      if (archives) {
        operations
            .put(
                ARCHIVE_WORKFLOW,
                PhaseOperationHandler
                    .of(
                        request -> phaseOne.add(request.args().get(ARG_ARCHIVE)),
                        request -> phaseTwo
                            .add(
                                "%s->%s".formatted(
                                    request.workflowAggregateId(), request.args().get(ARG_ARCHIVE)))));
      }
      return operations;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
      return WorkflowAwareness.ACTIVE;
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {
      return WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

  }

  private static class RecordingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new LinkedList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {
      scheduled.add(call);
      return true;
    }

  }

  private static class PersistenceStub implements AggregatePersistenceAware<Object> {

    @Override
    public Class<Object> getAggregateClass() {
      return Object.class;
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object save(
        final Object aggregate) {
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final Object aggregate) {
      return "4711";
    }

  }

  private MigrationProcessService<Object> processService(
      final MigratableProcessService<Object> adapter,
      final PhaseTwoOutbox outbox) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("archiving-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("archiving-adapter"))
        .build();
    properties.validateAndLink();

    final var resolver = new PhaseTwoOutboxResolver() {

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

    return new MigrationProcessService<Object>(
        MODULE, PROCESS, Object.class, properties, new PersistenceStub(), List.of(adapter), resolver);

  }

  @Test
  @DisplayName("Phase one of a new operation runs through the adapter's handler and is planned in the outbox")
  public void phaseOneRunsAndIsPlanned() {

    final var adapter = new ArchivingAdapter(true);
    final var outbox = new RecordingOutbox();

    processService(adapter, outbox)
        .execute(ARCHIVE_WORKFLOW, new Object(), Map.of(ARG_ARCHIVE, "cold-storage"));

    assertEquals(List.of("cold-storage"), adapter.phaseOne);
    // nothing reached the BPMS inside the caller's transaction
    assertTrue(adapter.phaseTwo.isEmpty());

    final var call = outbox.scheduled.getFirst();
    assertEquals("test:ARCHIVE_WORKFLOW", call.operation());
    assertEquals("4711", call.workflowAggregateId());
    assertEquals("cold-storage", call.args().get(ARG_ARCHIVE));
    // the operation's own key rule, applied by the core without knowing it
    assertEquals(
        "test:ARCHIVE_WORKFLOW|test-module|TestProcess|4711",
        call.idempotencyKey().orElseThrow());
    // the workflow is looked for again at dispatch time, so no adapter is persisted
    assertEquals(null, call.adapterId());

  }

  @Test
  @DisplayName("The router dispatches a new operation into the same handler's phase two")
  public void phaseTwoIsDispatchedThroughTheRouter() {

    final var adapter = new ArchivingAdapter(true);
    final var outbox = new RecordingOutbox();
    final var service = processService(adapter, outbox);

    final var router = new PhaseTwoRouter();
    router.registerOperation(ARCHIVE_WORKFLOW);
    router.register(service);

    service.execute(ARCHIVE_WORKFLOW, new Object(), Map.of(ARG_ARCHIVE, "cold-storage"));
    router.dispatch(outbox.scheduled.getFirst());

    assertEquals(List.of("4711->cold-storage"), adapter.phaseTwo);

  }

  @Test
  @DisplayName("An adapter without a handler for the operation says so, and says what to do instead")
  public void anAdapterWithoutTheHandlerSaysSo() {

    final var adapter = new ArchivingAdapter(false);

    final var exception = assertThrowsExactly(
        PhaseOperationNotSupported.class,
        () -> processService(adapter, new RecordingOutbox())
            .execute(ARCHIVE_WORKFLOW, new Object(), Map.of(ARG_ARCHIVE, "cold-storage")));

    assertTrue(exception.getMessage().contains("archiving-adapter"), exception.getMessage());
    assertTrue(exception.getMessage().contains("test:ARCHIVE_WORKFLOW"), exception.getMessage());
    assertTrue(exception.getMessage().contains("archiving into 'cold-storage'"), exception.getMessage());
    assertTrue(exception.getMessage().contains("keeps an archive"), exception.getMessage());

  }

}
