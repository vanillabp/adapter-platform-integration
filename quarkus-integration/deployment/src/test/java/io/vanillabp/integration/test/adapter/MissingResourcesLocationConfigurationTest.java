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
 * Same-config-same-outcome matrix (core validation on all platforms): a workflow
 * module without any resources-location reads its BPMN from the CONVENTIONAL
 * location instead of failing the boot (story 34). The workflow module here is
 * declared by the application itself (the test archive IS the root application
 * archive), so its BPMN lives below 'processes/' without a module-id namespace.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MissingResourcesLocationConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("missing-resources-location/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                               // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)                // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))               // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  MigrationAdapterProperties properties;

  @Test
  public void testResourcesLocationFollowsTheConvention() {

    final var resourcesLocation = properties.getAdapterResourcesLocationFor("test-module", "test");

    Assertions.assertEquals("classpath*:processes/test", resourcesLocation.location());
    Assertions.assertFalse(
        resourcesLocation.vanillaBpBpmn(),
        "a derived location is adapter-specific, like a configured adapter-specific one");

  }

}
