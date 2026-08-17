package io.vanillabp.migration.test.workflowend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;

/**
 * Telling the application that a workflow ended (story 43): the aggregate is loaded,
 * the method is called and the aggregate is saved - and everything about it is
 * optional, so a process without such a method reports nothing and an aggregate
 * already deleted is not an error.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowEndedTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final Instant END_TIME = Instant.parse("2026-08-13T09:15:00Z");

  public static class Aggregate {

    String id;

    String status;

    String endedAs;

    public String getId() {
      return id;
    }

    public void setStatus(
        final String status) {
      this.status = status;
    }

    public void setEndedAs(
        final String endedAs) {
      this.endedAs = endedAs;
    }

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    boolean saved = false;

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      saved = true;
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

    boolean requireNewUsed = false;

    boolean inCurrentUsed = false;

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      requireNewUsed = true;
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      inCurrentUsed = true;
      return work.get();
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  static class NoticingService {

    @WorkflowEnded
    public void ended(
        final Aggregate aggregate,
        final WorkflowEnd end) {

      aggregate.setStatus("ended");
      aggregate.setEndedAs("%s@%s/%s".formatted(end.kind(), end.time(), end.endEventId()));

    }

  }

  static class SilentService {

    // no @WorkflowEnded method: the application does not want to know

  }

  static class PerEndEventService {

    @WorkflowEnded(id = "Event_Approved")
    public void approved(
        final Aggregate aggregate) {

      aggregate.setStatus("approved");

    }

  }

  static class TwoMethodsForOneEndService {

    @WorkflowEnded
    public void first(
        final Aggregate aggregate) {
    }

    @WorkflowEnded
    public void second(
        final Aggregate aggregate) {
    }

  }

  static class ReturningService {

    @WorkflowEnded
    public String ended(
        final Aggregate aggregate) {

      return "nope";

    }

  }

  static class UnbindableParameterService {

    @WorkflowEnded
    public void ended(
        final Aggregate aggregate,
        final StringBuilder somethingElse) {
    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private MigrationProcessService<Aggregate> processService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("test-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("test-adapter"))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn("test-adapter");

    return new MigrationProcessService<>(
        MODULE, PROCESS, Aggregate.class, properties, persistence, List.of(adapter), null);

  }

  private WorkflowTaskRegistry registry(
      final Class<?> workflowServiceClass,
      final Supplier<Object> bean,
      final TransactionRunner transactionRunner) {

    final var registry = new WorkflowTaskRegistry(transactionRunner);
    registry
        .registerWorkflowService(MODULE, PROCESS, workflowServiceClass, bean, type -> null, processService());
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    aggregate.status = "running";
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  private WorkflowEndedContext context(
      final String aggregateId,
      final WorkflowEnd.Kind kind,
      final String endEventId,
      final boolean joinTransaction) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return kind;
      }

      @Override
      public Instant getEndTime() {
        return END_TIME;
      }

      @Override
      public String getEndEventId() {
        return endEventId;
      }

      @Override
      public boolean runInCurrentTransaction() {
        return joinTransaction;
      }

    };

  }

  @Test
  @DisplayName("The method is called with the aggregate and how the workflow ended, and the aggregate is saved")
  public void theApplicationIsToldTheEnd() {

    final var transactionRunner = new TransactionRunnerStub();
    final var testee = registry(NoticingService.class, NoticingService::new, transactionRunner);
    storeAggregate("4711");

    testee
        .workflowEnded(
            MODULE, PROCESS, context("4711", WorkflowEnd.Kind.COMPLETED, "Event_Done", false));

    final var aggregate = persistence.aggregates.get("4711");
    assertEquals("ended", aggregate.status);
    assertEquals("COMPLETED@%s/Event_Done".formatted(END_TIME), aggregate.endedAs);
    assertTrue(persistence.saved, "the aggregate has to be saved - that is what the method changed it for");
    assertTrue(transactionRunner.requireNewUsed);

  }

  @Test
  @DisplayName("A notification of an embedded BPMS joins the transaction which ended the workflow")
  public void embeddedBpmsJoinTheCallersTransaction() {

    final var transactionRunner = new TransactionRunnerStub();
    final var testee = registry(NoticingService.class, NoticingService::new, transactionRunner);
    storeAggregate("4712");

    testee
        .workflowEnded(
            MODULE, PROCESS, context("4712", WorkflowEnd.Kind.TERMINATED, null, true));

    assertTrue(transactionRunner.inCurrentUsed);
    assertFalse(transactionRunner.requireNewUsed);

  }

  @Test
  @DisplayName("Without a method nothing is registered and nothing is reported")
  public void withoutAMethodNothingHappens() {

    final var testee = registry(SilentService.class, SilentService::new, new TransactionRunnerStub());
    storeAggregate("4713");

    assertFalse(testee.workflowEndedHandlerExists(MODULE, PROCESS));
    // an adapter which notifies anyway (a deployed model outliving its workflow
    // service) must not fail
    testee
        .workflowEnded(MODULE, PROCESS, context("4713", WorkflowEnd.Kind.COMPLETED, null, false));
    assertFalse(persistence.saved);

  }

  @Test
  @DisplayName("An aggregate which does not exist any more is not an error")
  public void aDeletedAggregateIsNoError() {

    final var testee = registry(NoticingService.class, NoticingService::new, new TransactionRunnerStub());

    testee
        .workflowEnded(MODULE, PROCESS, context("gone", WorkflowEnd.Kind.COMPLETED, null, false));

    assertFalse(persistence.saved);

  }

  @Test
  @DisplayName("A method serving one end event ignores the ends of the others")
  public void endEventIdIsHonored() {

    final var testee = registry(PerEndEventService.class, PerEndEventService::new, new TransactionRunnerStub());
    storeAggregate("4714");

    testee
        .workflowEnded(
            MODULE, PROCESS, context("4714", WorkflowEnd.Kind.COMPLETED, "Event_Rejected", false));
    assertEquals("running", persistence.aggregates.get("4714").status);

    testee
        .workflowEnded(
            MODULE, PROCESS, context("4714", WorkflowEnd.Kind.COMPLETED, "Event_Approved", false));
    assertEquals("approved", persistence.aggregates.get("4714").status);

  }

  @Test
  @DisplayName("Two methods serving the same end are ambiguous and fail at startup")
  public void twoMethodsForOneEndFail() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry(
            TwoMethodsForOneEndService.class,
            TwoMethodsForOneEndService::new,
            new TransactionRunnerStub()));

    assertTrue(exception.getMessage().contains(TwoMethodsForOneEndService.class.getName()));
    assertTrue(exception.getMessage().contains("every end of the workflow"));

  }

  @Test
  @DisplayName("A method returning something fails at startup - a workflow which ended cannot be influenced")
  public void returningMethodFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry(ReturningService.class, ReturningService::new, new TransactionRunnerStub()));

    assertTrue(exception.getMessage().contains("void"));

  }

  @Test
  @DisplayName("A parameter which is neither the aggregate nor the end fails at startup")
  public void unbindableParameterFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry(
            UnbindableParameterService.class,
            UnbindableParameterService::new,
            new TransactionRunnerStub()));

    assertTrue(exception.getMessage().contains(WorkflowEnd.class.getName()));
    assertTrue(exception.getMessage().contains(Aggregate.class.getName()));

  }

  @Test
  @DisplayName("A notification for a process without any workflow service fails guiding")
  public void unknownProcessFailsGuiding() {

    final var testee = registry(NoticingService.class, NoticingService::new, new TransactionRunnerStub());

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> testee
            .workflowEnded(
                MODULE, "OtherProcess", context("4715", WorkflowEnd.Kind.COMPLETED, null, false)));

    assertTrue(exception.getMessage().contains("OtherProcess"));
    assertNull(persistence.aggregates.get("4715"));

  }

}
