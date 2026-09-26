package io.vanillabp.migration.test.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.extension.spi.handler.HandlerCall;
import io.vanillabp.integration.extension.spi.handler.HandlerContext;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The methods of an extension are chosen by the version of the BPMN process an event came
 * from, exactly as the <code>&#64;WorkflowTask</code> methods of an application are: the
 * same specifications, the same version tags, the same reading of what is ambiguous and
 * what is two generations of one model standing next to each other.
 * <p>
 * The implementation behind both is one class, so what is proven here about an extension
 * is the behaviour VanillaBP's own annotations have (see decision 56 in the repository's
 * DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerVersionsTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String EXTENSION = "sample";

  private static final String ADAPTER = "test-adapter";

  /**
   * The startup reports of these tests are written by loggers of the core, and this
   * module logs into no appender by default.
   */
  private ListAppender<ILoggingEvent> logWatcher;

  private Logger watched;

  @BeforeEach
  public void watchTheLog() {

    logWatcher = new ListAppender<>();
    logWatcher.start();
    watched = (Logger) LoggerFactory.getLogger("io.vanillabp.integration.adapter.migration");
    watched.addAppender(logWatcher);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    watched.detachAppender(logWatcher);
    logWatcher.stop();

  }

  private String reported() {

    return java.util.stream.Stream
        .concat(
            logWatcher.list
                .stream()
                .map(ILoggingEvent::getFormattedMessage),
            // what a check of the start found goes into the collection of the start
            // rather than into a line of its own
            io.vanillabp.migration.test.startup.WhatWasFound
                .entries(lastProperties.startupFindings())
                .stream())
        .collect(java.util.stream.Collectors.joining("\n"));

  }

  /**
   * The configuration the registry built last reports into.
   */
  private static MigrationAdapterProperties lastProperties;

  /**
   * The annotation of an extension which lets a method name the versions it serves.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Repeatable(Notes.class)
  public @interface Note {

    String element() default "";

    String[] version() default {};

  }

  /**
   * Holds the repetitions of {@link Note}.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Notes {

    Note[] value();

  }

  /**
   * The extension's own payload.
   */
  public record Payload(String text) {
  }

  /**
   * The workflow aggregate of these tests.
   */
  public static class Aggregate {

    String id;

    public String getId() {

      return id;

    }

    public void setId(
        final String id) {

      this.id = id;

    }

  }

  /**
   * Two generations of one model, told apart by the version each method names.
   */
  public static class VersionedService {

    @Note(element = "TheTask", version = "1")
    public String theOldTask(
        final Payload payload) {

      return "old/"
          + payload.text();

    }

    @Note(element = "TheTask", version = ">1")
    public String theNewTask(
        final Payload payload) {

      return "new/"
          + payload.text();

    }

  }

  /**
   * One method for a range of versions and nothing for the rest.
   */
  public static class RangedService {

    @Note(element = "TheTask", version = "1-3")
    public String theEarlyTask(
        final Payload payload) {

      return "early/"
          + payload.text();

    }

  }

  /**
   * A method for a version tag, which only the BPMS can place.
   */
  public static class TaggedService {

    @Note(element = "TheTask", version = "release-2024")
    public String theTaggedTask(
        final Payload payload) {

      return "tagged/"
          + payload.text();

    }

  }

  /**
   * Two methods for one element whose ranges share version 2.
   */
  public static class AmbiguousService {

    @Note(element = "TheTask", version = "1-2")
    public String theOneTask(
        final Payload payload) {

      return "one";

    }

    @Note(element = "TheTask", version = "2-3")
    public String theOtherTask(
        final Payload payload) {

      return "other";

    }

  }

  /**
   * A method for a version no BPMS of this application holds.
   */
  public static class UnreachableService {

    @Note(element = "TheTask", version = "5")
    public String theTaskOfAVersionNobodyHolds(
        final Payload payload) {

      return "five";

    }

  }

  /**
   * A method naming a version next to one naming none.
   */
  public static class MixedService {

    @Note(element = "TheTask", version = "2")
    public String onlyForTheSecondVersion(
        final Payload payload) {

      return "second";

    }

    @Note(element = "AnotherTask")
    public String forEveryVersion(
        final Payload payload) {

      return "every";

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
    public <T> T requireTransaction(
        final Supplier<T> work) {

      return work.get();

    }

    @Override
    public boolean isRollbackOnly() {

      return false;

    }

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

  /**
   * What one BPMS knows about the versions of the process - here two versions, the
   * second of them tagged.
   */
  static class TwoDeployedVersions implements ProcessVersionCatalog {

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return List
          .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2", "release-2024"));

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return deployedVersionsOf(workflowModuleId, bpmnProcessId)
          .stream()
          .filter(version -> versionOrVersionTag.equals(version.version()) || versionOrVersionTag
              .equals(version.versionTag()))
          .findFirst()
          .orElse(null);

    }

  }

  private static MigrationProcessService<Aggregate> processService(
      final AggregatePersistenceAware<Aggregate> persistence) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    final var adapter = new MigratableProcessService<Aggregate>() {

      @Override
      public String getAdapterId() {

        return ADAPTER;

      }

      @Override
      public Map<io.vanillabp.integration.spi.PhaseOperation, io.vanillabp.integration.adapter.spi.PhaseOperationHandler<Aggregate>> phaseOperations() {

        return io.vanillabp.migration.test.TestPhaseOperations.doingNothing();

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
          final AggregatePersistenceAware<Aggregate> aggregatePersistence,
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

    };
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  /**
   * The contract of these tests: the versions are read from the annotation's
   * <code>version</code> attribute, and the calls name the version they are about.
   */
  private static HandlerContract contract(
      final boolean callsCarryTheProcessVersion) {

    final var contract = HandlerContract
        .of(EXTENSION, Note.class)
        .lookupKeys(annotation -> ((Note) annotation).element().isEmpty()
            ? List.of()
            : List.of(((Note) annotation).element()))
        .versions(annotation -> List.of(((Note) annotation).version()))
        .parameterBinder(parameter -> parameter.getType().equals(Payload.class)
            ? Optional.of(HandlerContext::getPayload)
            : Optional.empty())
        .deliversReturnValue()
        .neverSavesTheWorkflowAggregate();
    if (callsCarryTheProcessVersion) {
      contract.callsCarryTheProcessVersion();
    }
    return contract.build();

  }

  private static WorkflowTaskRegistry registry(
      final Class<?> workflowServiceClass,
      final Supplier<Object> workflowServiceBean,
      final boolean callsCarryTheProcessVersion) {

    lastProperties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    lastProperties.validateAndLink();
    final var registry = new WorkflowTaskRegistry(
        new TransactionRunnerStub(), null, List.of(), lastProperties);
    final var persistence = new InMemoryPersistence();
    final var aggregate = new Aggregate();
    aggregate.setId("4711");
    persistence.save(aggregate);
    registry
        .getExtensionHandlers()
        .register(contract(callsCarryTheProcessVersion));
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            workflowServiceClass,
            workflowServiceBean,
            type -> null,
            processService(persistence));
    return registry;

  }

  private static HandlerCall call(
      final String elementId,
      final String processVersion) {

    return HandlerCall
        .of(Note.class, MODULE, PROCESS)
        .lookupKeys(List.of(elementId))
        .workflowAggregateId("4711")
        .processVersion(processVersion)
        .payload(new Payload("hello"))
        .build();

  }

  @Test
  @DisplayName("Two methods for two generations of one model are told apart by the version of the call")
  public void theVersionOfTheCallPicksTheMethod() {

    final var handlers = registry(VersionedService.class, VersionedService::new, true)
        .getExtensionHandlers();

    assertEquals("old/hello", handlers.invoke(call("TheTask", "1")).orElseThrow());
    assertEquals("new/hello", handlers.invoke(call("TheTask", "2")).orElseThrow());
    assertEquals("new/hello", handlers.invoke(call("TheTask", "7")).orElseThrow());

  }

  @Test
  @DisplayName("A range covers the versions between its boundaries and nothing else")
  public void aRangeCoversItsVersions() {

    final var handlers = registry(RangedService.class, RangedService::new, true)
        .getExtensionHandlers();

    assertEquals("early/hello", handlers.invoke(call("TheTask", "2")).orElseThrow());
    assertEquals("early/hello", handlers.invoke(call("TheTask", "3")).orElseThrow());
    // a version outside the range is served by nobody, which is an empty answer rather
    // than a failure: what to do instead is the extension's own business
    assertTrue(handlers.invoke(call("TheTask", "4")).isEmpty());
    assertFalse(handlers.hasHandler(Note.class, MODULE, PROCESS, List.of("TheTask"), "4"));
    assertTrue(handlers.hasHandler(Note.class, MODULE, PROCESS, List.of("TheTask"), "3"));

  }

  @Test
  @DisplayName("Methods for separate ranges stand next to each other, overlapping ones end the boot naming both")
  public void onlyOverlappingRangesAreAmbiguous() {

    // "1" next to ">1" is two generations of one model and boots
    registry(VersionedService.class, VersionedService::new, true);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> registry(AmbiguousService.class, AmbiguousService::new, true));

    final var said = refused.getMessage();
    assertTrue(said.contains("theOneTask"), said);
    assertTrue(said.contains("theOtherTask"), said);
    assertTrue(said.contains("'1-2'"), said);
    assertTrue(said.contains("'2-3'"), said);
    assertTrue(said.contains(EXTENSION), said);

  }

  @Test
  @DisplayName("A call naming no version reaches the methods naming none")
  public void aCallWithoutAVersionReachesTheMethodsNamingNone() {

    final var handlers = registry(MixedService.class, MixedService::new, true)
        .getExtensionHandlers();

    assertEquals("every", handlers.invoke(call("AnotherTask", null)).orElseThrow());
    // whether a version lies within a range nobody reported is not guessed
    assertTrue(handlers.invoke(call("TheTask", null)).isEmpty());
    assertEquals("second", handlers.invoke(call("TheTask", "2")).orElseThrow());

  }

  @Test
  @DisplayName("A version tag is placed by the BPMS, like a tag of a @WorkflowTask method")
  public void aVersionTagIsResolvedByTheBpms() {

    final var registry = registry(TaggedService.class, TaggedService::new, true);
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, new TwoDeployedVersions());
    registry.resolveProcessVersions(MODULE);

    final var handlers = registry.getExtensionHandlers();
    // version 2 carries the tag, version 1 does not
    assertEquals("tagged/hello", handlers.invoke(call("TheTask", "2")).orElseThrow());
    assertTrue(handlers.invoke(call("TheTask", "1")).isEmpty());

  }

  @Test
  @DisplayName("A method naming a version an extension never reports is said out loud at startup")
  public void aVersionNobodyReportsIsSaidAtStartup() {

    final var registry = registry(RangedService.class, RangedService::new, false);
    registry.resolveProcessVersions(MODULE);

    final var said = reported();
    assertTrue(said.contains("theEarlyTask"), said);
    assertTrue(said.contains("reports no process version"), said);
    assertTrue(said.contains("the method never runs"), said);

  }

  @Test
  @DisplayName("A method serving no version the BPMS holds is reported at startup, like one of VanillaBP's own")
  public void aMethodServingNoHeldVersionIsReported() {

    final var registry = registry(UnreachableService.class, UnreachableService::new, true);
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, new TwoDeployedVersions());
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "2");
    registry.resolveProcessVersions(MODULE);

    final var said = reported();
    assertTrue(said.contains("theTaskOfAVersionNobodyHolds"), said);
    assertTrue(said.contains("the method never runs"), said);
    assertTrue(said.contains(EXTENSION), said);

  }

}
