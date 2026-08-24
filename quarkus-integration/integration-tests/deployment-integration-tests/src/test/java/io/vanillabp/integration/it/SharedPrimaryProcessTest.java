package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

/**
 * The case which stays allowed: several classes may declare the same
 * aggregate as long as they name the SAME process - handlers of one process split
 * across classes are not ambiguous, so nothing has to be decided and the application
 * boots.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SharedPrimaryProcessTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addClass(SharedAggregate.class)
          .addClass(SharedAggregatePersistence.class)
          .addClass(FirstHalfOfTheProcess.class)
          .addClass(SecondHalfOfTheProcess.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/LoanApproval.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  ProcessService<SharedAggregate> processService;

  @Test
  @DisplayName("Two classes serving one process build one ProcessService for that process")
  public void sharedPrimaryProcessIsNotAmbiguous() {

    assertNotNull(processService);
    assertEquals(
        "LoanApproval",
        ((ProcessServiceBaseCdiBean<SharedAggregate>) processService).getBpmnProcessId());

  }

  @Getter
  @Setter
  public static class SharedAggregate {

    private String id;

  }

  @ApplicationScoped
  public static class SharedAggregatePersistence implements AggregatePersistenceAware<SharedAggregate> {

    @Override
    public Class<SharedAggregate> getAggregateClass() {

      return SharedAggregate.class;

    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = SharedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"))
  public static class FirstHalfOfTheProcess {
  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = SharedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"))
  public static class SecondHalfOfTheProcess {
  }

}
