package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.ProcessDefinitionIds;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * The core's part of the viewer/history API: electing the adapter
 * holding the workflow, namespacing the adapter-native process definition ids and
 * the guiding errors of the two SPI exceptions.
 */
@ExtendWith(MockitoExtension.class)
@ExtendWith(SuppressOutputExtension.class)
public class ViewerApiTest {

  /**
   * What a probe is asked about. Any scope does here: the adapters of this
   * test answer from what the test told them, not from a deployment.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

  @Mock
  private MigratableProcessService<Object> firstAdapter;

  @Mock
  private MigratableProcessService<Object> secondAdapter;

  @Mock
  private AggregatePersistenceAware<Object> aggregatePersistence;

  private final Object aggregate = new Object();

  private MigrationAdapterProperties createProperties() {

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

  private MigrationProcessService<Object> createProcessService() {

    when(firstAdapter.getAdapterId()).thenReturn("first-adapter");
    when(secondAdapter.getAdapterId()).thenReturn("second-adapter");
    return MigrationProcessService
        .forBpmnProcess("test-module", "TestProcess", Object.class)
        .properties(createProperties())
        .aggregatePersistence(aggregatePersistence)
        .processServices(List
            .of(firstAdapter, secondAdapter))
        .build();

  }

  /**
   * A process service with an election cache, which is what a hint needs to exist at
   * all - the adapter ids are stubbed leniently because a read answered by the hinted
   * adapter never asks the other one for its id.
   */
  private MigrationProcessService<Object> createProcessService(
      final WorkflowAdapterCache cache) {

    lenient().when(firstAdapter.getAdapterId()).thenReturn("first-adapter");
    lenient().when(secondAdapter.getAdapterId()).thenReturn("second-adapter");
    return MigrationProcessService
        .forBpmnProcess("test-module", "TestProcess", Object.class)
        .properties(createProperties())
        .aggregatePersistence(aggregatePersistence)
        .processServices(List
            .of(firstAdapter, secondAdapter))
        .workflowAdapterCache(cache)
        .build();

  }

  @Test
  @DisplayName("Process definitions are answered by the adapter knowing the workflow, ids namespaced by adapter id")
  public void processDefinitionsAreNamespacedPerAdapter() {

    final var processService = createProcessService();
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    // the first adapter does not know the workflow, the second one runs it
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);
    when(secondAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42")).thenReturn(WorkflowAwareness.ACTIVE);
    when(secondAdapter.getProcessDefinitions(
        eq("test-module"), eq("TestProcess"), any(), eq("42"), eq(null)))
        .thenReturn(
            List.of(
                new ProcessDefinition("native:1:abc", "TestProcess", "1", null),
                new ProcessDefinition("native:2:def", "SubProcess", "3", List.of("theCallActivity"))));

    final var definitions = processService.getProcessDefinitions(aggregate, null);

    assertEquals(2, definitions.size());
    assertEquals("second-adapter#native:1:abc", definitions.get(0).id());
    assertEquals("TestProcess", definitions.get(0).bpmnProcessId());
    assertNull(definitions.get(0).usedByElements());
    assertEquals("second-adapter#native:2:def", definitions.get(1).id());
    assertEquals(List.of("theCallActivity"), definitions.get(1).usedByElements());

  }

  @Test
  @DisplayName("A COMPLETED workflow is a valid subject of the viewer API")
  public void completedWorkflowsAreViewable() {

    final var processService = createProcessService();
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42")).thenReturn(WorkflowAwareness.COMPLETED);
    when(firstAdapter.getWorkflowHistory(
        eq("test-module"), eq("TestProcess"), any(), eq("42"), eq(null)))
        .thenReturn(
            new WorkflowHistory("native:1:abc", OffsetDateTime.now(), OffsetDateTime.now(), List.of()));

    final var history = processService.getWorkflowHistory(aggregate, null);

    assertEquals("first-adapter#native:1:abc", history.processDefinitionId());
    assertEquals(List.of(), history.elementsHistory());

  }

  @Test
  @DisplayName("A read waits out the visibility delay of the adapter a hint points at")
  public void readWaitsForAnEventuallyConsistentAdapterToCatchUp() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var processService = createProcessService(cache);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    // VanillaBP started this workflow there, which is why "not findable" is a read
    // model running behind rather than a workflow nobody ever heard of
    processService.rememberWorkflowAdapter("42", "first-adapter");
    when(firstAdapter.workflowVisibilityDelay())
        .thenReturn(new WorkflowVisibilityDelay(Duration.ofSeconds(5), Duration.ofMillis(20)));
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS)
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS)
        .thenReturn(WorkflowAwareness.ACTIVE);
    // a read which does not wait ends up here and fails - the answer this test is about
    lenient()
        .when(secondAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);
    when(firstAdapter.getWorkflowHistory(
        eq("test-module"), eq("TestProcess"), any(), eq("42"), eq(null)))
        .thenReturn(new WorkflowHistory("native:1:abc", OffsetDateTime.now(), null, List.of()));

    // a viewer opened right after the start is the ordinary case: nothing repeats this
    // read later, so waiting here is the only chance the exporter gets
    final var history = processService.getWorkflowHistory(aggregate, null);

    assertEquals("first-adapter#native:1:abc", history.processDefinitionId());
    assertNull(history.endTime());

  }

  @Test
  @DisplayName("A hint which never comes true fails the read naming the adapter which should hold the workflow")
  public void readFailsAfterTheVisibilityWindowPassed() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var processService = createProcessService(cache);
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    processService.rememberWorkflowAdapter("42", "first-adapter");
    lenient()
        .when(firstAdapter.workflowVisibilityDelay())
        .thenReturn(new WorkflowVisibilityDelay(Duration.ofMillis(100), Duration.ofMillis(20)));
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);
    when(secondAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var exception = assertThrowsExactly(
        WorkflowNotFoundException.class,
        () -> processService.getWorkflowHistory(aggregate, null));

    // the hinted adapter was asked more than once: the read used up the window before
    // giving up, which is the difference to an adapter nobody expects anything from
    verify(firstAdapter, atLeast(2)).awarenessOfWorkflow(SCOPE, aggregatePersistence, "42");
    // an exporter which stopped looks exactly like one which is behind, so the message
    // says which adapter was expected to answer and that its window has passed
    assertTrue(exception.getMessage().contains("first-adapter"));
    assertTrue(exception.getMessage().contains("workflowVisibilityDelay"));

  }

  @Test
  @DisplayName("A workflow unknown to every adapter raises a guiding WorkflowNotFoundException")
  public void unknownWorkflowRaisesGuidingError() {

    final var processService = createProcessService();
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);
    when(secondAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42"))
        .thenReturn(WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var exception = assertThrowsExactly(
        WorkflowNotFoundException.class,
        () -> processService.getProcessDefinitions(aggregate, null));

    assertTrue(exception.getMessage().contains("42"));
    assertTrue(exception.getMessage().contains("TestProcess"));
    assertTrue(exception.getMessage().contains("test-module"));
    assertTrue(exception.getMessage().contains("first-adapter"));

  }

  @Test
  @DisplayName("An adapter reporting the workflow but having no data raises a guiding WorkflowNotFoundException")
  public void adapterWithoutDataRaisesGuidingError() {

    final var processService = createProcessService();
    when(aggregatePersistence.getAggregateId(aggregate)).thenReturn("42");
    when(firstAdapter.awarenessOfWorkflow(SCOPE, aggregatePersistence, "42")).thenReturn(WorkflowAwareness.ACTIVE);
    when(firstAdapter.getWorkflowHistory(
        eq("test-module"), eq("TestProcess"), any(), eq("42"), eq("some-context")))
        .thenReturn(null);

    final var exception = assertThrowsExactly(
        WorkflowNotFoundException.class,
        () -> processService.getWorkflowHistory(aggregate, "some-context"));

    assertTrue(exception.getMessage().contains("first-adapter"));
    assertTrue(exception.getMessage().contains("some-context"));

  }

  @Test
  @DisplayName("The BPMN XML is fetched from the adapter named by the composite process definition id")
  public void bpmnXmlIsRoutedByTheCompositeId() {

    final var processService = createProcessService();
    when(secondAdapter.getBpmnXml("test-module", "TestProcess", "native:1:abc"))
        .thenReturn(new ByteArrayInputStream("<bpmn/>".getBytes(StandardCharsets.UTF_8)));

    final var xml = new String(
        readAllBytes(processService.getBpmnXml("second-adapter#native:1:abc")), StandardCharsets.UTF_8);

    assertEquals("<bpmn/>", xml);

  }

  @Test
  @DisplayName("A process definition id not following the scheme raises a guiding ProcessDefinitionNotFoundException")
  public void malformedProcessDefinitionIdRaisesGuidingError() {

    final var processService = createProcessService();

    final var exception = assertThrowsExactly(
        ProcessDefinitionNotFoundException.class,
        () -> processService.getBpmnXml("no-namespace-here"));

    assertTrue(exception.getMessage().contains("no-namespace-here"));
    assertTrue(exception.getMessage().contains("<adapter id>"));

  }

  @Test
  @DisplayName("A process definition id of an unconfigured adapter raises a guiding ProcessDefinitionNotFoundException")
  public void unknownAdapterOfProcessDefinitionIdRaisesGuidingError() {

    final var processService = createProcessService();

    final var exception = assertThrowsExactly(
        ProcessDefinitionNotFoundException.class,
        () -> processService.getBpmnXml("third-adapter#native:1:abc"));

    assertTrue(exception.getMessage().contains("third-adapter"));
    assertTrue(exception.getMessage().contains("first-adapter"));

  }

  @Test
  @DisplayName("A definition unknown to the addressed adapter raises a guiding ProcessDefinitionNotFoundException")
  public void unknownProcessDefinitionRaisesGuidingError() {

    final var processService = createProcessService();
    when(firstAdapter.getBpmnXml("test-module", "TestProcess", "native:1:abc")).thenReturn(null);

    final var exception = assertThrowsExactly(
        ProcessDefinitionNotFoundException.class,
        () -> processService.getBpmnXml("first-adapter#native:1:abc"));

    assertTrue(exception.getMessage().contains("first-adapter"));
    assertTrue(exception.getMessage().contains("native:1:abc"));

  }

  @Test
  @DisplayName("The composite id scheme keeps adapter-native ids containing separators intact")
  public void compositeIdSchemeRoundTrips() {

    final var composite = ProcessDefinitionIds.compose("my-adapter", "demo:1:8a9c#weird");

    assertEquals("my-adapter#demo:1:8a9c#weird", composite);

    final var parsed = ProcessDefinitionIds.parse(composite);

    assertEquals("my-adapter", parsed.adapterId());
    assertEquals("demo:1:8a9c#weird", parsed.nativeProcessDefinitionId());
    assertNull(ProcessDefinitionIds.parse(null));
    assertNull(ProcessDefinitionIds.parse("#no-adapter-id"));
    assertNull(ProcessDefinitionIds.parse("no-native-id#"));
    assertNull(ProcessDefinitionIds.compose("my-adapter", null));

  }

  private static byte[] readAllBytes(
      final java.io.InputStream inputStream) {

    try (inputStream) {
      return inputStream.readAllBytes();
    } catch (final java.io.IOException e) {
      throw new IllegalStateException(e);
    }

  }

}
