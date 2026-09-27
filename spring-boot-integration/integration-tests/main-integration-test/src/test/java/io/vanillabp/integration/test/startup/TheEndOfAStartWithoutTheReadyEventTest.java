package io.vanillabp.integration.test.startup;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * The end of a start does not wait for the application to be ready.
 * <p>
 * A context which is only refreshed never fires
 * {@link org.springframework.boot.context.event.ApplicationReadyEvent}, and as long as the
 * end of the start hung on that event such a context got neither the block of what the
 * start noticed nor the refusals it collected. It now hangs on the lifecycle, which every
 * refresh runs, so both arrive here as they arrive in an application.
 * <p>
 * The start of the workflow processing is the other half and stays on the event: what is
 * pinned here is only that the two are no longer the same moment.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheEndOfAStartWithoutTheReadyEventTest {

  /**
   * An extension which refuses while the BPMN files are wired: a reason not to start
   * which is found DURING the deployment, so it is neither known before the deployment
   * begins nor reported by a bean the refresh builds. It is collected, and only the end
   * of the start can throw it.
   */
  @Configuration
  static class RefusingExtensionConfiguration {

    static final String WHY_IT_REFUSES = "this extension refuses while it wires, to prove where a start ends";

    @Bean
    ExtensionWiringService<Object, Object> refusingExtension(
        final StartupReport report) {

      return new ExtensionWiringService<Object, Object>() {

        @Override
        public Class<Object> getModelType() {

          return Object.class;

        }

        @Override
        public Class<Object> getProcessContextType() {

          return Object.class;

        }

        @Override
        public void wireBpmn(
            final String workflowModuleId,
            final String filename,
            final String bpmnProcessId,
            final Object model,
            final Object context) {

          report.refuse(StartupTopic.CODE, "workflow module '%s'".formatted(workflowModuleId), WHY_IT_REFUSES);

        }

        @Override
        public void startWorkflowProcessing(
            final String workflowModuleId,
            final Object bpmsProcessingContext) {

          // nothing to start: this extension exists for what it says while it wires

        }

      };

    }

  }

  private ApplicationContextRunner contextRunner(
      final Class<?>... userConfigurations) {

    final var configurations = new java.util.LinkedList<Class<?>>(
        java.util.List.of(
            WorkflowModuleConfiguration.class,
            TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class));
    configurations.addAll(java.util.List.of(userConfigurations));

    return new ApplicationContextRunner()
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(configurations.toArray(Class<?>[]::new))
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                // the lifecycle bean which deploys and ends the start
                DeploymentAutoConfiguration.class));

  }

  @Test
  public void aContextWhichIsOnlyRefreshedWritesTheBlock(
      final CapturedOutput output) {

    contextRunner()
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "this context has nothing to refuse");

          // the test application has more BPMN processes than workflow services, so there
          // is something to say and the block says it
          Assertions.assertTrue(
              output.getAllOfThisTest().contains("looked at this application and found"),
              "a refreshed context has to get the block, printed: "
                  + output.getAllOfThisTest());

        });

  }

  @Test
  public void aRefusalCollectedWhileDeployingEndsARefreshedContext(
      final CapturedOutput output) {

    contextRunner(RefusingExtensionConfiguration.class)
        .run(context -> {

          Assertions.assertNotNull(
              context.getStartupFailure(),
              "a collected refusal has to end a refresh, too");

          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          Assertions.assertTrue(
              String.valueOf(cause.getMessage()).contains(RefusingExtensionConfiguration.WHY_IT_REFUSES),
              "the refusal of the extension has to be the reason, but the start ended on: "
                  + cause);

          // the box is written before the refusal is thrown, so what the start noticed
          // besides the refusal is not lost with it
          Assertions.assertTrue(
              output.getAllOfThisTest().contains("looked at this application and found"),
              "the block belongs into the log of a refused start as well, printed: "
                  + output.getAllOfThisTest());

        });

  }

}
