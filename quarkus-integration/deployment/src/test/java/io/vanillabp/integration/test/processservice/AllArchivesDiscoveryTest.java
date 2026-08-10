package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Workflow services have to be found no matter which archive of the Quarkus build they
 * live in: the root archive (the application's own classes) as well as any JAR the
 * application depends on. This test pins that contract down for the archive scanning
 * done by the build steps of this module, which is easy to break unnoticed - scanning
 * the dependency archives only would still boot fine for applications whose workflow
 * services all sit in JARs, and scanning the root archive only would still boot fine
 * for single-module applications.
 * <p>
 * Both workflow services rely on the workflow module descriptor of the ROOT archive,
 * so the module ID reported by the process service of the dependency archive
 * additionally proves the fallback to the global classpath.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AllArchivesDiscoveryTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(DummyAdapters.class)                            // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class)             // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)             // process service of the mocked adapter
          .addPackage("io.vanillabp.integration.test.samples.sample")  // workflow service of the ROOT archive
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE))
      // the second workflow service is part of a DEPENDENCY archive and brings no
      // workflow module descriptor of its own
      .withAdditionalDependency(dependency -> dependency
          .addPackage("io.vanillabp.integration.test.samples.sample2"))
      // the classes of the additional dependency are on the test classpath as well;
      // without a flat class path they would be loaded twice, by the base class loader
      // and by the runtime one
      .setFlatClassPath(true)
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  ProcessService<io.vanillabp.integration.test.samples.sample.Aggregate> processServiceOfRootArchive;

  @Inject
  ProcessService<io.vanillabp.integration.test.samples.sample2.Aggregate> processServiceOfDependencyArchive;

  @Test
  public void testWorkflowServicesOfAllArchivesAreFound() {

    Assertions.assertNotNull(processServiceOfRootArchive);
    Assertions.assertNotNull(processServiceOfDependencyArchive);
    Assertions.assertNotEquals(processServiceOfRootArchive, processServiceOfDependencyArchive);

    Assertions.assertEquals(
        "test-module",
        workflowModuleIdOf(processServiceOfRootArchive));
    Assertions.assertEquals(
        "test-module",
        workflowModuleIdOf(processServiceOfDependencyArchive));

  }

  /**
   * Reads the workflow module the process service was built for from the task
   * registrations recorded at build time (<code>module|class|bpmn process id</code>).
   */
  private static String workflowModuleIdOf(
      final ProcessService<?> processService) {

    final var registrations = ((ProcessServiceBaseCdiBean<?>) processService)
        .getWorkflowTaskRegistrations();
    return registrations.substring(0, registrations.indexOf('|'));

  }

}
