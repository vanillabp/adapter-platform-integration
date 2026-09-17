package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.Getter;
import lombok.Setter;

/**
 * An aggregate which keeps nothing back hands every attribute to the BPMS, and nobody
 * decided that. The boot ends, and the message names the workflow, the aggregate, the
 * attributes which travel and the property line allowing them.
 * <p>
 * {@code FullSyncAllowedTest} is the other half: the same shape of application starts
 * where its workflow allows the full sync.
 */
@ExtendWith(SuppressOutputExtension.class)
public class FullSyncWithoutPermissionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("task-processing/application.yaml", "application.yaml")
          .addClass(FullSyncAggregate.class)
          .addClass(FullSyncAggregatePersistence.class)
          .addClass(FullSyncWorkflowService.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/FullSyncProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("shares EVERY attribute")) {
            assertTrue(current.getMessage().contains(FullSyncAggregate.class.getName()));
            assertTrue(current.getMessage().contains("'FullSyncProcess'"));
            assertTrue(current.getMessage().contains("cardNumber"));
            assertTrue(current.getMessage().contains("@NoSyncWithBPMS"));
            assertTrue(
                current
                    .getMessage()
                    .contains(
                        "vanillabp.workflow-modules.test-module.workflows.FullSyncProcess.allow-full-sync-with-bpms: true"));
            return;
          }
          current = current.getCause();
        }
        fail("expected the refusal of the full sync but got: "
            + throwable);
      });

  @Test
  @DisplayName("An aggregate sharing everything ends the boot with a guiding message")
  public void fullSyncWithoutAPermissionEndsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

  @Getter
  @Setter
  public static class FullSyncAggregate {

    private String id;

    private String customer;

    private String cardNumber;

  }

  @ApplicationScoped
  public static class FullSyncAggregatePersistence implements AggregatePersistenceAware<FullSyncAggregate> {

    @Override
    public Class<FullSyncAggregate> getAggregateClass() {
      return FullSyncAggregate.class;
    }

    @Override
    public FullSyncAggregate save(
        final FullSyncAggregate aggregate) {
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final FullSyncAggregate aggregate) {
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
    public FullSyncAggregate loadById(
        final Object aggregateId) {
      return null;
    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = FullSyncAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "FullSyncProcess"))
  public static class FullSyncWorkflowService {

    @WorkflowTask
    public void approve(
        final FullSyncAggregate aggregate) {
    }

  }

}
