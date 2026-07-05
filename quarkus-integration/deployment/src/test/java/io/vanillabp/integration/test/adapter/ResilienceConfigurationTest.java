package io.vanillabp.integration.test.adapter;

import java.time.Duration;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.ResilienceProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import jakarta.inject.Inject;

/**
 * Tests that the resilience block is resolved on all levels (global / workflow module)
 * and the per-adapter deployment-failure policy is mapped by the Quarkus integration.
 */
public class ResilienceConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("resilience-configuration/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter()); // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  public void testResilienceResolvedOnAllLevels() {

    // global resilience block
    final var global = properties.getResilienceFor(null, null);
    Assertions.assertEquals(5, global.getMaxRetries());
    Assertions.assertEquals(Duration.ofSeconds(2), global.getInitialInterval());
    Assertions.assertEquals(1.5, global.getMultiplier());
    Assertions.assertEquals(Duration.ofSeconds(10), global.getTimeout());

    // workflow module resilience block overrides the global block as a whole
    final var module = properties.getResilienceFor("test-module", null);
    Assertions.assertEquals(9, module.getMaxRetries());
    Assertions.assertEquals(ResilienceProperties.DEFAULT_INITIAL_INTERVAL, module.getInitialInterval());

    // workflow level not configured: workflow module block applies
    final var workflow = properties.getResilienceFor("test-module", "SampleWorkflow");
    Assertions.assertEquals(9, workflow.getMaxRetries());

  }

  @Test
  public void testDeploymentFailurePolicy() {

    Assertions.assertEquals(
        DeploymentFailurePolicy.WARN,
        properties.getDeploymentFailureFor("test"));
    Assertions.assertEquals(
        DeploymentFailurePolicy.FAIL,
        properties.getDeploymentFailureFor("other"));

  }

}
