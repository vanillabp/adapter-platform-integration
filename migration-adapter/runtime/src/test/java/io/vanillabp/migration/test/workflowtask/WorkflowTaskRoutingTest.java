package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Which <code>&#64;WorkflowTask</code> method serves a delivery.
 * <p>
 * A method is wired by one of two keys and says which: <code>taskDefinition</code> names what
 * the BPMS subscribed to, <code>id</code> names the BPMN element. A delivery reports both,
 * and the routing has to match each key against the key of its own kind - the very pair the
 * wiring validation matches a method against while the application boots.
 * <p>
 * Until it did, a service task wired by an expression whose method named the element id
 * passed the boot without a word and failed at the first delivery. That failure reached the
 * application as an incident of the BPMS, which is the worst place to learn about a wiring
 * defect.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskRoutingTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "IdWiredProcess";

  private static final String ADAPTER = "test-adapter";

  public static class Aggregate {

    @Getter
    String id;

    String servedBy;

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

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

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  /**
   * One method wired by the element id, one wired by a task definition - the two ways an
   * application writes a handler, in one class.
   */
  @WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @io.vanillabp.spi.service.BpmnProcess(
      bpmnProcessId = PROCESS))
  public static class BothWirings {

    @WorkflowTask(id = "IW_Task")
    public void servedByTheElementId(
        final Aggregate aggregate) {

      aggregate.servedBy = "element id";

    }

    @WorkflowTask(taskDefinition = "aJobTypeSomebodyNamed")
    public void servedByTheTaskDefinition(
        final Aggregate aggregate) {

      aggregate.servedBy = "task definition";

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private MigrationProcessService<Aggregate> processService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient()
        .when(adapter.getAdapterId())
        .thenReturn(ADAPTER);

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  private WorkflowTaskRegistry registryOfBothWirings() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry
        .registerWorkflowService(MODULE, PROCESS, BothWirings.class, BothWirings::new, type -> null, processService());
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  /**
   * What an adapter reports about one delivery, both keys given.
   */
  private static TaskInvocationContext delivery(
      final String aggregateId,
      final String taskDefinition,
      final String bpmnElementId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getBpmnElementId() {
        return bpmnElementId;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

    };

  }

  @Test
  @DisplayName("A method wired by the element id serves a task the BPMS subscribed to by name")
  public void aMethodWiredByTheElementIdServesTheTask() {

    final var registry = registryOfBothWirings();
    final var aggregate = storeAggregate("4711");

    registry
        .invokeWorkflowTask(MODULE, PROCESS, delivery("4711", "theExpressionNobodyNames", "IW_Task"));

    assertEquals(
        "element id",
        aggregate.servedBy,
        "the delivery names the element the method is wired to, so that method serves it");

  }

  @Test
  @DisplayName("A method wired by a task definition keeps serving the task definition")
  public void aMethodWiredByATaskDefinitionStillServesIt() {

    final var registry = registryOfBothWirings();
    final var aggregate = storeAggregate("4712");

    registry
        .invokeWorkflowTask(MODULE, PROCESS, delivery("4712", "aJobTypeSomebodyNamed", "AnotherElement"));

    assertEquals("task definition", aggregate.servedBy);

  }

  @Test
  @DisplayName("An adapter naming no element still reaches a method wired by the element id")
  public void anAdapterNamingNoElementStillReachesTheIdWiredMethod() {

    final var registry = registryOfBothWirings();
    final var aggregate = storeAggregate("4713");

    // the one value such an adapter reports IS the element id, which is what the routing
    // has always assumed and what an adapter without getBpmnElementId still relies on
    registry.invokeWorkflowTask(MODULE, PROCESS, delivery("4713", "IW_Task", null));

    assertEquals("element id", aggregate.servedBy);

  }

  @Test
  @DisplayName("A delivery nobody serves is refused with both of its keys")
  public void aDeliveryNobodyServesIsRefusedWithBothKeys() {

    final var registry = registryOfBothWirings();
    storeAggregate("4714");

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> registry
            .invokeWorkflowTask(MODULE, PROCESS, delivery("4714", "somethingElse", "AnUnwiredElement")));

    assertTrue(
        refused.getMessage().contains("'somethingElse'"),
        "the task definition the BPMS reported: %s".formatted(refused.getMessage()));
    assertTrue(
        refused.getMessage().contains("'AnUnwiredElement'"),
        "and the element of the model, which is the name the developer searches for: %s"
            .formatted(refused.getMessage()));

  }

}
