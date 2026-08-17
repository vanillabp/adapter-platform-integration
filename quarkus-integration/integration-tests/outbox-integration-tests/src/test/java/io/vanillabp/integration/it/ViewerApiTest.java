package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PerAdapterAwarenessSource;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SteerableViewerSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.inject.Inject;

/**
 * Acceptance test of the viewer/history API (story 26) on Quarkus: the BPMS
 * holding the workflow answers the read - even though it is NOT the
 * first-priority adapter - and the process definition ids handed to the
 * application are namespaced per adapter id so {@code getBpmnXml} stays
 * resolvable without an aggregate to elect by.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ViewerApiTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("viewer-api.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(PerAdapterAwarenessSource.class)
          .addClass(SteerableViewerSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:viewer-api-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  PerAdapterAwarenessSource awareness;

  @Inject
  SteerableViewerSource viewerSource;

  @Inject
  AggregatePersistence aggregatePersistence;

  @BeforeEach
  public void reset() {

    awareness.reset();
    viewerSource.reset();

  }

  @Test
  @DisplayName("The BPMS holding the workflow serves definitions, BPMN XML and history - ids namespaced per adapter")
  public void viewerApiIsServedByTheBpmsHoldingTheWorkflow() {

    final var aggregate = new Aggregate();
    aggregate.setContent("viewed");
    aggregatePersistence.save(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    // the workflow lives in the DEMOTED adapter (started before a priority flip)
    awareness.answerFor("old-bpms", WorkflowAwareness.ACTIVE);
    viewerSource.serve("old-bpms", aggregateId);

    final var definitions = workflowService.getProcessDefinitions(aggregate, null);

    assertEquals(2, definitions.size());
    assertEquals("old-bpms#"
        + SteerableViewerSource.NATIVE_DEFINITION_ID, definitions.get(0).id());
    assertNull(definitions.get(0).usedByElements());
    assertEquals(List.of("theCallActivity"), definitions.get(1).usedByElements());

    try (var xml = workflowService.getBpmnXml(definitions.get(0).id())) {
      assertEquals(SteerableViewerSource.BPMN_XML, new String(xml.readAllBytes(), StandardCharsets.UTF_8));
    } catch (final java.io.IOException e) {
      throw new IllegalStateException(e);
    }

    final var history = workflowService.getWorkflowHistory(aggregate, null);

    assertEquals("old-bpms#"
        + SteerableViewerSource.NATIVE_DEFINITION_ID, history.processDefinitionId());
    assertEquals(2, history.elementsHistory().size());
    assertEquals("sub-instance-1", history.elementsHistory().get(1).secondaryWorkflowHistoryContext());

  }

  @Test
  @DisplayName("A workflow no BPMS knows raises a guiding WorkflowNotFoundException")
  public void unknownWorkflowRaisesGuidingError() {

    final var aggregate = new Aggregate();
    aggregate.setContent("unknown");
    aggregatePersistence.save(aggregate);

    final var exception = assertThrows(
        WorkflowNotFoundException.class,
        () -> workflowService.getProcessDefinitions(aggregate, null));

    assertTrue(
        exception.getMessage().contains(String.valueOf(aggregate.getId())),
        () -> "expected a guiding message but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("A process definition id of an unconfigured adapter raises a guiding ProcessDefinitionNotFoundException")
  public void unknownAdapterOfDefinitionIdRaisesGuidingError() {

    final var exception = assertThrows(
        ProcessDefinitionNotFoundException.class,
        () -> workflowService.getBpmnXml("third-bpms#"
            + SteerableViewerSource.NATIVE_DEFINITION_ID));

    assertTrue(
        exception.getMessage().contains("third-bpms"),
        () -> "expected a guiding message but got: "
            + exception.getMessage());

  }

}
