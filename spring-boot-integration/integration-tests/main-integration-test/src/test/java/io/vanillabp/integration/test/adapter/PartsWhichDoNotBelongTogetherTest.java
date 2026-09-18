package io.vanillabp.integration.test.adapter;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

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
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * An application whose VanillaBP parts were never built together does not start, and the
 * message it stops with is what this test pins: the versions of both sides and the
 * dependency to change. A message which only says that something does not fit leaves the
 * developer with the same search as the <code>NoSuchMethodError</code> the check is there
 * to prevent.
 * <p>
 * The adapter of this test ships
 * <code>src/test/resources/META-INF/vanillabp/adapter-from-the-future.properties</code>,
 * which claims a platform integration nobody has. The other tests of this module are
 * unaffected: a version descriptor says nothing about an adapter which is not there, and
 * this adapter is only a bean in this test.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PartsWhichDoNotBelongTogetherTest {

  /**
   * An adapter built against a platform integration which does not exist yet. Nothing is
   * ever asked of it: the boot ends while the parts are counted, which is before the
   * deployment pipeline reaches any adapter.
   */
  static class AdapterFromTheFuture implements AdapterDeploymentService<String, String> {

    @Override
    public String getAdapterId() {

      return "from-the-future";

    }

    @Override
    public String getAdapterType() {

      return "from-the-future";

    }

    @Override
    public Class<String> getModelType() {

      return String.class;

    }

    @Override
    public Class<String> getProcessContextType() {

      return String.class;

    }

    @Override
    public List<Map.Entry<String, String>> readBpmn(
        final String workflowModuleId,
        final String filename,
        final InputStream bpmn,
        final boolean isVanillaBpBpmn) {

      throw new UnsupportedOperationException("the boot never gets here");

    }

    @Override
    public String prepareBpmn(
        final String workflowModuleId,
        final String existingContext,
        final String filename,
        final String bpmnProcessId,
        final String model) {

      throw new UnsupportedOperationException("the boot never gets here");

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final String model,
        final String context) {

      throw new UnsupportedOperationException("the boot never gets here");

    }

    @Override
    public void deployResources(
        final String workflowModuleId,
        final String bpmsProcessingContext) {

      throw new UnsupportedOperationException("the boot never gets here");

    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final String bpmsProcessingContext) {

      throw new UnsupportedOperationException("the boot never gets here");

    }

  }

  @Configuration
  static class AdapterFromTheFutureConfiguration {

    @Bean
    AdapterDeploymentService<String, String> adapterFromTheFuture() {

      return new AdapterFromTheFuture();

    }

  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void anApplicationWhosePartsDoNotBelongTogetherDoesNotStart() {

    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class, TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class, AdapterFromTheFutureConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                DeploymentAutoConfiguration.class))
        .run(context -> {

          final var failure = context.getStartupFailure();
          Assertions.assertNotNull(failure, "an application whose parts do not belong together must not start");

          final var said = whatWasSaid(failure);
          // both sides, by name and by version
          Assertions.assertTrue(said.contains("adapter 'from-the-future' 9.9.9"), said);
          Assertions.assertTrue(said.contains("platform integration 99.0.0"), said);
          Assertions.assertTrue(said.contains("does not start"), said);
          // and the line to change
          Assertions.assertTrue(said.contains("io.vanillabp:vanillabp-bom"), said);
          Assertions.assertTrue(said.contains("io.vanillabp:vanillabp-spring-boot-integration"), said);
          Assertions.assertTrue(said.contains("io.vanillabp.test:adapter-from-the-future"), said);

        });

  }

  /**
   * @param failure The exception the boot ended with
   * @return Everything the exception and its causes say
   */
  private static String whatWasSaid(
      final Throwable failure) {

    final var said = new StringBuilder();
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      said.append(cause.getMessage()).append(System.lineSeparator());
      if (cause.getCause() == cause) {
        break;
      }
    }
    return said.toString();

  }

}
