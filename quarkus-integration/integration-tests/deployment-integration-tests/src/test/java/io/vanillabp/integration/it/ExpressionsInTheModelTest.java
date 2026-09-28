package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

/**
 * An application whose BPMN model reads its data with expressions learns at the start what
 * that binds - here through a Quarkus start, where {@code ModelExpressionCheckTest} of the
 * core reads the messages alone and the Spring Boot twin of this class boots the other
 * platform.
 * <p>
 * {@code AcceptedExpressionsTest} is the other half: the same application, and a workflow
 * saying its expressions are meant as they are.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExpressionsInTheModelTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("expressions/application.yaml", "application.yaml")
          .addClass(ShippingAggregate.class)
          .addClass(ShippingAggregatePersistence.class)
          .addClass(ShippingWorkflowService.class)
          .addClass(ShippingWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/ShippingProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        final var reported = messages
            .stream()
            .filter(message -> message.contains("more than the name of one variable"))
            .toList();
        assertEquals(1, reported.size(), "one box holding the finding: "
            + messages);
        final var box = reported.getFirst();
        assertTrue(box.contains("'${order.shipping.express}' at 'Flow_Express'"), box);
        assertTrue(box.contains("'${order.getItems()}' at 'Activity_Pack'"), box);
        assertTrue(box.contains("the collection of a multi-instance element"), box);
        assertTrue(box.contains("isShippedAsNormalItem()"), box);
        assertTrue(box.contains("2 of the 5 expressions of this process name a variable"), box);
        assertTrue(box.contains("compute instead of naming a variable"), box);
        assertTrue(box.contains("'${not bigItem}' at 'Flow_Small'"), box);
        assertTrue(
            box
                .contains(
                    "vanillabp.workflow-modules.test-module.workflows.ShippingProcess.accept-expressions-in-the-model"),
            box);
        // the two expressions doing what VanillaBP recommends are counted, never named
        assertFalse(box.contains("'Flow_Normal'"), box);
        assertFalse(box.contains("'Flow_Big'"), box);
      });

  @Inject
  ShippingWorkflowService workflowService;

  @Test
  @DisplayName("The application starts, and the findings are in the box of its start")
  public void theApplicationStartsAndSaysWhatItFound() {

    // the findings themselves are asserted on the boot's log records (assertLogRecords
    // above); what matters here is that a model full of expressions still starts
    assertTrue(workflowService != null, "the application booted");

  }

  @Getter
  @Setter
  public static class ShippingAggregate {

    private String id;

    private boolean bigItem;

    public boolean isShippedAsNormalItem() {

      return !bigItem;

    }

  }

  @ApplicationScoped
  public static class ShippingAggregatePersistence implements AggregatePersistenceAware<ShippingAggregate> {

    @Override
    public Class<ShippingAggregate> getAggregateClass() {
      return ShippingAggregate.class;
    }

    @Override
    public ShippingAggregate save(
        final ShippingAggregate aggregate) {
      return aggregate;
    }

    @Override
    public Object getAggregateId(
        final ShippingAggregate aggregate) {
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
    public ShippingAggregate loadById(
        final Object aggregateId) {
      return null;
    }

  }

  @ApplicationScoped
  @WorkflowService(
      workflowAggregateClass = ShippingAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "ShippingProcess"))
  public static class ShippingWorkflowService {

    @WorkflowTask(taskDefinition = "packItems")
    public void packItems(
        final ShippingAggregate aggregate) {

    }

  }

  /**
   * What the adapter reads off the model: two expressions reaching into the data, one
   * computing, and two which do what VanillaBP recommends.
   */
  @ApplicationScoped
  public static class ShippingWiringSource implements DummyTaskWiringSource {

    @Override
    public Collection<BpmnTaskSpec> tasksOf(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId) {

      return List.of(new BpmnTaskSpec("Activity_Pack", "packItems"));

    }

    @Override
    public Collection<ModelExpression> expressionsOf(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId) {

      return List
          .of(
              ModelExpression
                  .of("Flow_Express", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${order.shipping.express}"),
              ModelExpression
                  .of("Activity_Pack", ExpressionPlace.MULTI_INSTANCE_COLLECTION, "${order.getItems()}"),
              ModelExpression.of("Flow_Small", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${not bigItem}"),
              ModelExpression
                  .of("Flow_Normal", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${shippedAsNormalItem}"),
              ModelExpression.of("Flow_Big", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${bigItem}"));

    }

  }

}
