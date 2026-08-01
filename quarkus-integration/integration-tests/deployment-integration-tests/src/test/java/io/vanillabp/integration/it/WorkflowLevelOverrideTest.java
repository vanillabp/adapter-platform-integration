package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Workflow-level properties on Quarkus (story 27): the workflow module prioritizes
 * adapter id 'demo1' while the workflow 'WorkflowService' overrides to 'demo2' - the
 * classic migration scenario of moving a single process to a new BPMS while the rest
 * of the module stays. The deployment-target union guarantees 'demo2', although named
 * at the workflow level only, receives the module's resources and starts processing -
 * otherwise starting that workflow would fail at runtime.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLevelOverrideTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("workflow-level-override/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          // the dummy adapter derives the BPMN process ID from the filename - name
          // the file after the workflow so the configured workflow ID is known
          .addAsResource("bpmn/first.bpmn", "processes/dummy/WorkflowService.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingDeploymentEvents events;

  @Inject
  WorkflowService workflowService;

  @Inject
  ProcessService<Aggregate> processService;

  @Test
  @DisplayName("An adapter named at the workflow level only is deployed (union) and elected for that workflow")
  public void workflowLevelOverrideExtendsDeploymentAndWinsElection() {

    final var recorded = events.getEvents();

    // deployment-target union: 'demo2' is named at the workflow level ONLY but
    // still receives the module's resources and starts processing
    assertTrue(recorded.contains("adapter:demo1:deployResources:test-module"), recorded.toString());
    assertTrue(recorded.contains("adapter:demo2:deployResources:test-module"), recorded.toString());
    assertTrue(recorded.contains("adapter:demo1:startWorkflowProcessing:test-module"), recorded.toString());
    assertTrue(recorded.contains("adapter:demo2:startWorkflowProcessing:test-module"), recorded.toString());

    // the workflow-level override wins the election of this workflow's process service
    final var migrationProcessService = ((ProcessServiceBaseCdiBean<Aggregate>) processService)
        .getMigrationProcessService();
    assertEquals("WorkflowService", migrationProcessService.getBpmnProcessId());
    assertEquals(
        List.of("demo2", "demo1"),
        migrationProcessService.getPrioritizedAdapters());

    // starting THAT workflow succeeds (phase one runs against 'demo2' which got the
    // module's deployment via the union)
    final var aggregate = workflowService.startWorkflow("workflow-level-override");
    assertNotNull(aggregate.getId());

  }

}
