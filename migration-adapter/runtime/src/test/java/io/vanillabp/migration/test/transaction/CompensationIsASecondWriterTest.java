package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Compensation is the second token nobody warned about. A throw event which compensates two
 * finished activities starts both handlers, the workflow holds a token per handler, and each
 * of them writes the same workflow aggregate - the very situation
 * {@link io.vanillabp.integration.adapter.migration.transaction.ConcurrentTokenCheck} is
 * about, drawn with an element its list did not know.
 * <p>
 * Reading the model is the adapter's job, so the shape arrives through the wiring SPI; what
 * it means is decided here.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CompensationIsASecondWriterTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String THROW_EVENT = "UndoEverything";

  private static final String FIRST_HANDLER = "CancelBooking";

  private static final String SECOND_HANDLER = "RefundPayment";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * Stands in for <code>jakarta.persistence.Version</code> respectively
   * <code>org.springframework.data.annotation.Version</code>, which the core must not depend
   * on - it recognizes them by their name.
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target({
      java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.METHOD
  })
  public @interface Version {
  }

  /**
   * The same aggregate with the attribute a persistence layer increments per write - the
   * collision raises an exception there instead of being lost, which is what silences the
   * warning.
   */
  @Getter
  public static class VersionedAggregate {

    String id;

    @Version
    long version;

  }

  public static class Service {

    @WorkflowTask(taskDefinition = "cancelBooking")
    public void cancelBooking(
        final Aggregate aggregate) {
    }

  }

  public static class VersionedService {

    @WorkflowTask(taskDefinition = "cancelBooking")
    public void cancelBooking(
        final VersionedAggregate aggregate) {
    }

  }

  private MigrationAdapterProperties properties;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c8", AdapterConfigProperties.ofType("camunda8")));

  }

  @Test
  @DisplayName("A throw event starting two handlers names the process, the event and the handlers")
  public void aThrowEventStartingTwoHandlersIsReported() {

    final var entries = whatTheCheckSaidAbout(
        Aggregate.class,
        List.of(new CompensationSpec(THROW_EVENT, List.of(FIRST_HANDLER, SECOND_HANDLER))));

    assertEquals(1, entries.size(), entries.toString());
    final var entry = entries.getFirst();
    assertTrue(entry.contains("process '%s' of workflow module '%s'".formatted(PROCESS, MODULE)), entry);
    assertTrue(entry.contains("the compensation throw event '%s'".formatted(THROW_EVENT)), entry);
    assertTrue(entry.contains("'%s', '%s'".formatted(FIRST_HANDLER, SECOND_HANDLER)), entry);
    assertTrue(entry.contains(Aggregate.class.getName()), entry);

  }

  @Test
  @DisplayName("A throw event starting ONE handler is no finding")
  public void aThrowEventStartingOneHandlerIsQuiet() {

    assertEquals(
        List.of(),
        whatTheCheckSaidAbout(
            Aggregate.class, List.of(new CompensationSpec(THROW_EVENT, List.of(FIRST_HANDLER)))));

  }

  @Test
  @DisplayName("An adapter which reports no compensation is not guessed about")
  public void anAdapterReportingNothingIsQuiet() {

    assertEquals(List.of(), whatTheCheckSaidAbout(Aggregate.class, List.of()));

  }

  @Test
  @DisplayName("An aggregate with a version attribute stays quiet about compensation too")
  public void anAggregateWithAVersionAttributeIsQuiet() {

    assertEquals(
        List.of(),
        whatTheCheckSaidAbout(
            VersionedAggregate.class,
            List.of(new CompensationSpec(THROW_EVENT, List.of(FIRST_HANDLER, SECOND_HANDLER)))));

  }

  @Test
  @DisplayName("Every throw event of one process is named in one finding")
  public void everyThrowEventIsNamed() {

    final var entries = whatTheCheckSaidAbout(
        Aggregate.class,
        List
            .of(
                new CompensationSpec(THROW_EVENT, List.of(FIRST_HANDLER, SECOND_HANDLER)),
                new CompensationSpec("UndoTheTrip", List.of("CancelSeat", "CancelMeal"))));

    assertEquals(1, entries.size(), entries.toString());
    assertTrue(entries.getFirst().contains(THROW_EVENT), entries.toString());
    assertTrue(entries.getFirst().contains("UndoTheTrip"), entries.toString());

  }

  @Test
  @DisplayName("Compensation and a forking gateway of one process become ONE entry of the box")
  public void compensationFoldsWithTheOtherForms() {

    final var registry = registryServing(Aggregate.class);
    registry
        .validateTaskWiring(
            MODULE, PROCESS, List.of(new BpmnTaskSpec(FIRST_HANDLER, "cancelBooking")));
    registry.reportConcurrentTokenElements(MODULE, PROCESS, List.of("Gateway_Fork"));
    registry
        .reportCompensation(
            MODULE,
            PROCESS,
            List.of(new CompensationSpec(THROW_EVENT, List.of(FIRST_HANDLER, SECOND_HANDLER))));

    // two shapes of one finding about one aggregate, so they carry the same text and differ
    // in their scope - which is what lets the box fold them into one entry
    final var aboutTwoTokens = properties
        .startupFindings()
        .findings()
        .stream()
        .filter(finding -> finding.message().contains("hold more than one token"))
        .toList();
    assertEquals(2, aboutTwoTokens.size(), aboutTwoTokens.toString());
    final var box = properties.startupFindings().theBox();
    assertEquals(1, box.split("hold more than one token", -1).length - 1, box);
    assertTrue(box.contains("Gateway_Fork"), box);
    assertTrue(box.contains(THROW_EVENT), box);

  }

  /**
   * What the check said while an adapter wired a model reporting the given compensation - the
   * scope of a finding beside its message, because the scope is where the throw event and its
   * handlers stand.
   */
  private List<String> whatTheCheckSaidAbout(
      final Class<?> workflowAggregateClass,
      final Collection<CompensationSpec> compensations) {

    final var registry = registryServing(workflowAggregateClass);
    registry
        .validateTaskWiring(
            MODULE, PROCESS, List.of(new BpmnTaskSpec(FIRST_HANDLER, "cancelBooking")));
    registry.reportCompensation(MODULE, PROCESS, compensations);
    return io.vanillabp.migration.test.startup.WhatWasFound
        .entries(properties.startupFindings())
        .stream()
        .filter(entry -> entry.contains("hold more than one token"))
        .toList();

  }

  private WorkflowTaskRegistry registryServing(
      final Class<?> workflowAggregateClass) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    final var serviceClass = workflowAggregateClass == VersionedAggregate.class
        ? VersionedService.class
        : Service.class;
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            serviceClass,
            () -> null,
            type -> null,
            processService(workflowAggregateClass));
    return registry;

  }

  /**
   * The registry needs a process service to register a workflow service; nothing here invokes
   * it, and its aggregate class is the whole point of the question asked.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<?> processService(
      final Class<?> workflowAggregateClass) {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) workflowAggregateClass);
    org.mockito.Mockito
        .when(processService.detectsConcurrentModification())
        .thenReturn(io.vanillabp.integration.spi.VersionAttribute.isDeclaredBy(workflowAggregateClass));
    return processService;

  }

  /**
   * The transaction runner is irrelevant here - no test in this class runs a handler.
   */
  private static class TransactionRunnerStub implements TransactionRunner {

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

}
