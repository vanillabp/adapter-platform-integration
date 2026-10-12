package io.vanillabp.integration.test.deployment;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.test.workflowlevel.OverriddenAggregate;
import io.vanillabp.integration.test.workflowlevel.OverriddenWorkflowService;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of workflow-level properties. The workflow module prioritizes adapter id
 * 'test', and ONE workflow ('DummyProcess') overrides it with 'test2'. That is the classic
 * migration scenario: a single process moves to a new BPMS, and the rest of the module stays.
 * <ul>
 * <li>Deployment-target union: 'test2' is named at the workflow level only but still
 * receives the module's {@code deployResources}.</li>
 * <li>Election: starting THAT workflow uses 'test2', other workflows use the
 * module-level 'test'.</li>
 * <li>Guiding validation: a configured workflow ID which matches no BPMN process gives a
 * WARN at startup. It names the known process IDs (checked in a separate run).</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLevelOverrideTest {

  /**
   * Persistence doubles for both aggregates (the stubbed SpringDataUtil of
   * {@link TestPersistenceConfiguration} fails loudly on any use).
   */
  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<Aggregate> testAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Aggregate save(
            final Aggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate aggregate) {
          return "4711";
        }

      };

    }

    @Bean
    AggregatePersistenceAware<OverriddenAggregate> overriddenAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<OverriddenAggregate> getAggregateClass() {
          return OverriddenAggregate.class;
        }

        @Override
        public OverriddenAggregate save(
            final OverriddenAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final OverriddenAggregate aggregate) {
          return "0815";
        }

      };

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
          test2:
            type: dummy
        workflow-modules:
          test-module:
            prioritized-adapters:
              - test
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
            workflows:
              DummyProcess:
                prioritized-adapters:
                  - test2
                  - test
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp.applicationBuilder(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TestTransactionRunnerConfiguration.class,
        SampleWorkflowService.class,
        OverriddenWorkflowService.class,
        WorkflowModuleConfiguration.class,
        AggregatePersistenceConfiguration.class,
        DeploymentTest.TestConfig.class)
        .run();

  }

  @Test
  public void workflowLevelOverrideRoutesStartsAndExtendsDeployment(
      final CapturedOutput output) throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      // deployment-target union: 'test2' is named at the workflow level ONLY but
      // still receives the module's resources
      //
      // Everything read below is the output of the running test alone. The sentence
      // which must be absent says that adapter id 'test' starts a workflow. The second
      // test of this class configures that id as well. Only the narrow view keeps the
      // two boots apart.
      final var capturedOutput = output.getAllOfThisTest();
      Assertions.assertTrue(
          capturedOutput.contains("Dummy-Adapter[test]: Deploying resources for test-module"),
          "expected deployment of module-level id 'test' but got: "
              + capturedOutput);
      Assertions.assertTrue(
          capturedOutput.contains("Dummy-Adapter[test2]: Deploying resources for test-module"),
          "expected deployment of workflow-level id 'test2' but got: "
              + capturedOutput);

      // starting the OVERRIDDEN workflow uses 'test2' and never touches 'test'
      @SuppressWarnings("unchecked")
      final ProcessService<OverriddenAggregate> overriddenProcessService = (ProcessService<OverriddenAggregate>) context
          .getBeanProvider(ResolvableType
              .forClassWithGenerics(ProcessService.class, OverriddenAggregate.class))
          .getObject();
      overriddenProcessService.startWorkflow(new OverriddenAggregate());

      final var afterOverriddenStart = output.getAllOfThisTest();
      Assertions.assertTrue(
          afterOverriddenStart.contains("Dummy-Adapter[test2]: Starting workflow (phase one)"),
          "expected phase one of 'DummyProcess' on the workflow-level first-priority id 'test2' but got: "
              + afterOverriddenStart);
      Assertions.assertFalse(
          afterOverriddenStart.contains("Dummy-Adapter[test]: Starting workflow (phase one)"),
          "id 'test' must never be touched for 'DummyProcess' but got: "
              + afterOverriddenStart);

      // starting ANOTHER workflow of the same module uses the module-level 'test'
      @SuppressWarnings("unchecked")
      final ProcessService<Aggregate> sampleProcessService = (ProcessService<Aggregate>) context
          .getBeanProvider(ResolvableType
              .forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();
      sampleProcessService.startWorkflow(new Aggregate());

      final var afterSampleStart = output.getAllOfThisTest();
      Assertions.assertTrue(
          afterSampleStart.contains("Dummy-Adapter[test]: Starting workflow (phase one)"),
          "expected phase one of 'SampleWorkflowService' on the module-level id 'test' but got: "
              + afterSampleStart);

    }

  }

  private static final String UNKNOWN_WORKFLOW_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
          test2:
            type: dummy
        workflow-modules:
          test-module:
            prioritized-adapters:
              - test
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
            workflows:
              NoSuchProcess:
                prioritized-adapters:
                  - test2
      """;

  @Test
  public void unknownConfiguredWorkflowIdYieldsGuidingWarn(
      final CapturedOutput output) throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", UNKNOWN_WORKFLOW_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      // boots anyway, because the BPMN may arrive later (e.g. during a BPMS migration).
      // But it WARNs with the property key and the known BPMN process IDs
      final var capturedOutput = output.getAll();
      Assertions.assertTrue(
          capturedOutput.contains("vanillabp.workflow-modules.test-module.workflows.NoSuchProcess"),
          "expected a WARN naming the unused workflow property key but got: "
              + capturedOutput);
      Assertions.assertTrue(
          capturedOutput.contains("'DummyProcess'"),
          "expected the WARN to name the known BPMN process IDs but got: "
              + capturedOutput);

    }

  }

}
