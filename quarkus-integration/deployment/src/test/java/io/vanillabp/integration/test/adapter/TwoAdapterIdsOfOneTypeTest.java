package io.vanillabp.integration.test.adapter;

import java.util.List;
import java.util.Set;

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
 * Acceptance test of the bean convention "one instance per adapter id" on Quarkus (the
 * adapter config model). The migration scenario is built on it: TWO adapter ids of ONE
 * type boot together. An <i>element</i> bean ({@link TestMigratableProcessService}) serves
 * id 'test'. A bean of type <code>List&lt;MigratableProcessService&gt;</code>
 * ({@link Test2ListProcessServiceProducer}) serves id 'test2'. The platform collects the
 * List beans and flattens them alongside the element beans. If nobody served a prioritized
 * id, the election would fail fast and stop the boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterIdsOfOneTypeTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // two adapter ids of the type 'dummy'; the module prioritizes 'test2'
          .addAsResource("two-adapter-ids/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class) // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)             // element bean serving id 'test'
          .addClass(Test2ListProcessServiceProducer.class))         // List bean serving id 'test2'
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());    // add mocked adapter

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> processService;

  @Test
  public void twoIdsOfOneTypeAreServedByPerIdInstances() {

    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, processService);

    final var migrationProcessService = ((ProcessServiceBaseCdiBean<Aggregate>) processService)
        .getMigrationProcessService();

    // both configured ids of the dummy type are known and served. The election in
    // MigrationProcessService did not fail fast, although only a flattened List bean
    // provides 'test2'
    Assertions.assertEquals(
        Set.of("test", "test2"),
        migrationProcessService.getAdapters().keySet());

    // the workflow module prioritizes id 'test2', so new workflows start there
    Assertions.assertEquals(
        List.of("test2", "test"),
        migrationProcessService.getPrioritizedAdapters());

  }

}
