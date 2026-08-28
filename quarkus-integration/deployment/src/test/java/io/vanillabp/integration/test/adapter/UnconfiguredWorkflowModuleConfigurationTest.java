package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * A section for a workflow module which is NOT in the classpath stays a WARN (the
 * module may arrive later), while the module actually found in the classpath needs
 * no section at all - its own one is derived.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnconfiguredWorkflowModuleConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("unconfigured-workflow-module/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)                // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))               // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  public void testConfiguredAndDerivedWorkflowModulesCoexist() {

    // the configured module which is not in the classpath is kept (a WARN reports it)
    Assertions.assertTrue(properties.getWorkflowModules().containsKey("my-module"));
    // the module found in the classpath got its section derived
    Assertions.assertTrue(
        properties.getWorkflowModules().containsKey("test-module"),
        () -> "expected a derived section for the workflow module found in classpath but got: "
            + properties.getWorkflowModules().keySet());

  }

}
