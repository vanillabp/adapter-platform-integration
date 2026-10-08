package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.deployment.CallingAndCalledWiringSource;
import io.vanillabp.integration.test.deployment.CallingWorkflowService;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * A BPMN file carries two executable processes - which is what a modeller produces by
 * drawing a called process next to the calling one, and what a migration leaves behind
 * when a process moves out of an application while its model stays in the file. The file
 * travels to the BPMS as a whole, so both processes are deployed.
 * <p>
 * The application says with <code>implemented-externally=true</code> that something else
 * serves the second process. Then the application starts and says nothing about that
 * process, and the process next to it is wired as before, which its task running through the
 * core proves. Without the line the start ends, see
 * {@link UnclaimedBpmnProcessEndsTheBootTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnclaimedBpmnProcessTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("unclaimed-process/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(CallingWorkflowService.class)
          .addClass(CallingAndCalledWiringSource.class)
          .addAsResource("bpmn/calling-and-called.bpmn", "processes/unclaimed/CallingAndCalled.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        assertTrue(
            messages
                .stream()
                .noneMatch(message -> message.contains(UnclaimedBpmnProcessEndsTheBootTest.REFUSAL)),
            "a process the application marked is not reported: "
                + messages);
      });

  @Inject
  TaskAggregatePersistence persistence;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> "demo1".equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  @Test
  @DisplayName("The application boots, and the claimed process of the same file works")
  public void theClaimedProcessOfTheSameFileWorks() {

    // the silence about the marked process is asserted on the boot's log records; here the
    // process the application does serve has to behave as it did before
    persistence.seed("4711");
    final var outcome = dummyAdapter()
        .invokeTask("test-module", "Calling", new TaskInvocationContext() {

          @Override
          public String getTaskDefinition() {
            return "juhu";
          }

          @Override
          public String getWorkflowAggregateId() {
            return "4711";
          }

        });

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
    assertEquals("processed", persistence.stored("4711").getStatus());

  }

}
