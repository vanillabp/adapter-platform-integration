package io.vanillabp.integration.test.adapter;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Acceptance test of workflow-level properties (story 27, formerly rejected as "not
 * yet supported"): the workflow module prioritizes adapter id 'test' while the
 * workflow 'SampleWorkflowService' overrides to 'test2' - the resolution is performed
 * ONCE, by the core on the bound tree, so this CDI-level test proves the Quarkus
 * binding feeds the workflow level into the election.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLevelConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // module prioritizes 'test', workflow 'SampleWorkflowService' overrides to 'test2'
          .addAsResource("workflow-level-configuration/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class) // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)             // element bean serving id 'test'
          .addClass(Test2ListProcessServiceProducer.class))         // List bean serving id 'test2'
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> processService;

  @Test
  public void workflowLevelPrioritizedAdaptersAreResolved() {

    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, processService);

    final var migrationProcessService = ((ProcessServiceBaseCdiBean<Aggregate>) processService)
        .getMigrationProcessService();

    // the workflow-level override wins over the module-level list ('test' first)
    Assertions.assertEquals("SampleWorkflowService", migrationProcessService.getBpmnProcessId());
    Assertions.assertEquals(
        List.of("test2", "test"),
        migrationProcessService.getPrioritizedAdapters());

  }

}
