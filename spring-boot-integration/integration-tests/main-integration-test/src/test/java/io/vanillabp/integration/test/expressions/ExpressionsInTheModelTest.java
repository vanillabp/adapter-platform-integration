package io.vanillabp.integration.test.expressions;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * An application whose BPMN model reads its data with expressions learns at the start what
 * that binds. This is the whole way through a Spring Boot start: the adapter reports the
 * expressions of the model it wires, the core sorts them and the box says it once.
 * {@code ModelExpressionCheckTest} reads the messages alone, and
 * {@code ExpressionsInTheModelTest} of the Quarkus deployment tests holds the same behaviour
 * on the other platform.
 * <p>
 * The counter-check is the second start: the same model, and a workflow which says its
 * expressions are meant as they are, which leaves nothing to report.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExpressionsInTheModelTest {

  private static final String PROCESS = "ShippingProcess";

  /**
   * What the adapter reads off the model: two expressions reaching into the data, one
   * computing, and two which do what VanillaBP recommends.
   */
  @Configuration
  static class ShippingWiringConfiguration {

    @Bean
    DummyTaskWiringSource shippingWiringSource() {

      return new DummyTaskWiringSource() {

        @Override
        public List<BpmnTaskSpec> tasksOf(
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
                      .of(
                          "Flow_Express",
                          ExpressionPlace.SEQUENCE_FLOW_CONDITION,
                          "${order.shipping.express}"),
                  ModelExpression
                      .of(
                          "Activity_Pack",
                          ExpressionPlace.MULTI_INSTANCE_COLLECTION,
                          "${order.getItems()}"),
                  ModelExpression
                      .of("Flow_Small", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${not bigItem}"),
                  ModelExpression
                      .of(
                          "Flow_Normal",
                          ExpressionPlace.SEQUENCE_FLOW_CONDITION,
                          "${shippedAsNormalItem}"),
                  ModelExpression
                      .of("Flow_Big", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "${shippedAsBigItem}"));

        }

      };

    }

  }

  @Getter
  public static class ShippingAggregate {

    private String id;

    private boolean bigItem;

    public boolean isShippedAsNormalItem() {

      return !bigItem;

    }

    public boolean isShippedAsBigItem() {

      return bigItem;

    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = ShippingAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class ShippingWorkflowService {

    @WorkflowTask(taskDefinition = "packItems")
    public void packItems(
        final ShippingAggregate aggregate) {

    }

  }

  private static final String WITHOUT_ACCEPTANCE = """
      vanillabp:
        prioritized-adapters:
          - test
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/expressions
            workflows:
              ShippingProcess:
                allow-full-sync-with-bpms: true
      """;

  /**
   * The same configuration plus the line the message hands out - written at the workflow,
   * which is where the two keys of this process stand.
   */
  private static final String ACCEPTED_AT_THE_WORKFLOW = WITHOUT_ACCEPTANCE
      + "          accept-expressions-in-the-model: true\n";

  /**
   * The same line one level up, where it covers every workflow of the module.
   */
  private static final String ACCEPTED_AT_THE_WORKFLOW_MODULE = WITHOUT_ACCEPTANCE
      + "      accept-expressions-in-the-model: true\n";

  /**
   * And at the application, for a team which decided it once for everything they model.
   */
  private static final String ACCEPTED_AT_THE_APPLICATION = WITHOUT_ACCEPTANCE
      + "  accept-expressions-in-the-model: true\n";

  private SpringBootTestApplication buildTestApp(
      final String applicationYaml) throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", applicationYaml)
        .addResource("test-module/processes/expressions/ShippingProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            WorkflowModuleConfiguration.class,
            DeploymentTest.TestConfig.class,
            ShippingWiringConfiguration.class,
            ShippingWorkflowService.class)
        .run();

  }

  @Test
  @DisplayName("A path and a call are a WARN, a computation is a NOTICE, and a plain name is neither")
  public void whatTheExpressionsOfAModelCost(
      final CapturedOutput output) throws IOException {

    final var writtenBeforeThisBoot = output.getAll().length();

    try (var testApp = buildTestApp(WITHOUT_ACCEPTANCE); var context = runTestApplication(testApp)) {

      final var captured = output.getAll().substring(writtenBeforeThisBoot);
      Assertions
          .assertTrue(
              captured.contains("read the application's data by more than the name of one variable"),
              "expected the WARN about the path and the call but got: "
                  + captured);
      Assertions.assertTrue(captured.contains("'${order.shipping.express}' at 'Flow_Express'"), captured);
      Assertions.assertTrue(captured.contains("'${order.getItems()}' at 'Activity_Pack'"), captured);
      Assertions.assertTrue(captured.contains("the collection of a multi-instance element"), captured);
      Assertions.assertTrue(captured.contains("isShippedAsNormalItem()"), captured);
      Assertions
          .assertTrue(
              captured.contains("2 of the 5 expressions of this process name a variable"),
              "expected the count of the plain names but got: "
                  + captured);
      Assertions
          .assertTrue(
              captured.contains("compute instead of naming a variable"),
              "expected the NOTICE about the computation but got: "
                  + captured);
      Assertions.assertTrue(captured.contains("'${not bigItem}' at 'Flow_Small'"), captured);
      Assertions
          .assertTrue(
              captured
                  .contains(
                      "vanillabp.workflow-modules.test-module.workflows.ShippingProcess.accept-expressions-in-the-model"),
              "expected the key which accepts them but got: "
                  + captured);
      // the two expressions doing what VanillaBP recommends are counted, never named
      Assertions.assertFalse(captured.contains("'Flow_Normal'"), captured);
      Assertions.assertFalse(captured.contains("'Flow_Big'"), captured);

    }

  }

  @Test
  @DisplayName("The acceptance is read at the workflow, at the workflow module and at the application")
  public void anAcceptedModelIsSilentAtEveryLevel(
      final CapturedOutput output) throws IOException {

    for (final var applicationYaml : List
        .of(ACCEPTED_AT_THE_WORKFLOW, ACCEPTED_AT_THE_WORKFLOW_MODULE, ACCEPTED_AT_THE_APPLICATION)) {

      final var writtenBeforeThisBoot = output.getAll().length();

      try (var testApp = buildTestApp(applicationYaml); var context = runTestApplication(testApp)) {

        final var captured = output.getAll().substring(writtenBeforeThisBoot);
        // a level which binds but is never read looks exactly like a level which works,
        // so every one of the three is started here
        Assertions.assertFalse(captured.contains("more than the name of one variable"), captured);
        Assertions.assertFalse(captured.contains("compute instead of naming a variable"), captured);

      }

    }

  }

}
