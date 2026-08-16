package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Story 60: one workflow aggregate has one {@code ProcessService}, so one of the
 * classes declaring the aggregate names the process {@code startWorkflow} starts.
 * That used to be whichever class was found first - an order coming from the file
 * system. Two classes naming DIFFERENT processes now end the build with a message
 * naming both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AmbiguousPrimaryProcessTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(AmbiguousAggregate.class)
          .addClass(AmbiguousAggregatePersistence.class)
          .addClass(CallingWorkflowService.class)
          .addClass(CalledWorkflowService.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("declare a DIFFERENT BPMN process")) {
            final var message = current.getMessage();
            assertTrue(message.contains(CallingWorkflowService.class.getName()), message);
            assertTrue(message.contains(CalledWorkflowService.class.getName()), message);
            assertTrue(message.contains("LoanApproval"), message);
            assertTrue(message.contains("RiskAssessment"), message);
            assertTrue(message.contains("secondaryBpmnProcesses"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the build to report the ambiguous primary process but got: "
            + throwable);
      });

  @Test
  @DisplayName("Two processes declared for one aggregate end the build naming both classes")
  public void ambiguousPrimaryProcessFailsTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

  public static class AmbiguousAggregate {

    private String id;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
    }

  }

  @ApplicationScoped
  public static class AmbiguousAggregatePersistence implements AggregatePersistenceAware<AmbiguousAggregate> {

    @Override
    public Class<AmbiguousAggregate> getAggregateClass() {

      return AmbiguousAggregate.class;

    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = AmbiguousAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"))
  public static class CallingWorkflowService {
  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = AmbiguousAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "RiskAssessment"))
  public static class CalledWorkflowService {
  }

}
