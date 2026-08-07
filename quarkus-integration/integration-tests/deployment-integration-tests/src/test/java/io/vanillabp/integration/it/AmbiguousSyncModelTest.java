package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Story 28b: an aggregate whose attributes are annotated BOTH ways while the class
 * itself states no mode cannot be interpreted - is the rest shared with the BPMS or
 * not? The boot is aborted with a message naming the class, the conflicting
 * attributes and the fix, and it happens at STARTUP (when the workflow aggregate is
 * registered) instead of at the first sync point.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AmbiguousSyncModelTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("task-processing/application.yaml", "application.yaml")
          .addClass(AmbiguousAggregate.class)
          .addClass(AmbiguousAggregatePersistence.class)
          .addClass(AmbiguousWorkflowService.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TaskProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("does not state its own mode")) {
            assertTrue(current.getMessage().contains(AmbiguousAggregate.class.getName()));
            assertTrue(current.getMessage().contains("'customerName'"));
            assertTrue(current.getMessage().contains("'creditCardNumber'"));
            assertTrue(current.getMessage().contains("Annotate the CLASS"));
            return;
          }
          current = current.getCause();
        }
        fail("expected the guiding sync-model message but got: "
            + throwable);
      });

  @Test
  @DisplayName("An ambiguous sync model aborts the boot with a guiding message")
  public void ambiguousSyncModelAbortsBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

  public static class AmbiguousAggregate {

    private String id;

    @SyncWithBPMS
    private String customerName;

    @NoSyncWithBPMS
    private String creditCardNumber;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
    }

    public String getCustomerName() {
      return customerName;
    }

    public String getCreditCardNumber() {
      return creditCardNumber;
    }

  }

  @ApplicationScoped
  public static class AmbiguousAggregatePersistence implements AggregatePersistenceAware<AmbiguousAggregate> {

    @Override
    public Class<AmbiguousAggregate> getAggregateClass() {
      return AmbiguousAggregate.class;
    }

    @Override
    public AmbiguousAggregate save(
        final AmbiguousAggregate aggregate) {
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final AmbiguousAggregate aggregate) {
      return aggregate.getId();
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public AmbiguousAggregate loadById(
        final Object aggregateId) {
      return null;
    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = AmbiguousAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"))
  public static class AmbiguousWorkflowService {

    @WorkflowTask
    public void processTask(
        final AmbiguousAggregate aggregate) {
    }

  }

}
