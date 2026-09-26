package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.Setter;

/**
 * An application hands the BPMS a decimal and asks for a parameter of no type at all,
 * and it says nothing about either. The boot ends, and the message names the value, its
 * type, the direction it travels and the property line declaring it.
 * <p>
 * {@code DeclaredValuesTest} is the other half: the same application starts once it says
 * that it looked at both. The same behaviour is held for Spring Boot by
 * {@code PortableValuesTest} of the main integration test.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UndeclaredValuesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("portable-values/application.yaml", "application.yaml")
          .addClass(LoanAggregate.class)
          .addClass(LoanAggregatePersistence.class)
          .addClass(LoanApprovalWorkflowService.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"),
              "processes/dummy/LoanApprovalProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current
              .getMessage()
              .contains("moves values between the application and the BPMS")) {
            final var message = current.getMessage();
            assertTrue(message.contains("'amount'"), message);
            assertTrue(message.contains("java.math.BigDecimal"), message);
            assertTrue(message.contains("TO the BPMS"), message);
            assertTrue(message.contains("'LoanApprovalProcess'"), message);
            assertTrue(message.contains("'test-module'"), message);
            assertTrue(
                message
                    .contains(
                        "vanillabp.workflow-modules.test-module.workflows.LoanApprovalProcess.declared-aggregate-values"),
                message);
            assertTrue(message.contains("@TaskParam 'riskReport'"), message);
            assertTrue(message.contains("java.lang.Object"), message);
            assertTrue(message.contains("names no type at all"), message);
            assertTrue(
                message
                    .contains(
                        "vanillabp.workflow-modules.test-module.workflows.LoanApprovalProcess.tasks.<task>.declared-task-params"),
                message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the refusal of the values which do not travel but got: "
            + throwable);
      });

  @Test
  @DisplayName("Values nobody declared end the boot with a guiding message")
  public void undeclaredValuesEndTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

  /**
   * An aggregate which keeps one attribute back, so the full-sync check is happy and this
   * test is about the types of the values alone.
   */
  @Getter
  @Setter
  public static class LoanAggregate {

    private String id;

    private String region;

    private BigDecimal amount;

    @NoSyncWithBPMS
    private String internalNote;

  }

  @ApplicationScoped
  public static class LoanAggregatePersistence implements AggregatePersistenceAware<LoanAggregate> {

    @Override
    public Class<LoanAggregate> getAggregateClass() {
      return LoanAggregate.class;
    }

    @Override
    public LoanAggregate save(
        final LoanAggregate aggregate) {
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final LoanAggregate aggregate) {
      return aggregate.getId();
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public LoanAggregate loadById(
        final Object aggregateId) {
      return null;
    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApprovalProcess"))
  public static class LoanApprovalWorkflowService {

    @WorkflowTask(taskDefinition = "assessRisk")
    public void assessRisk(
        final LoanAggregate aggregate,
        @TaskParam("riskReport") final Object riskReport) {
    }

  }

}
