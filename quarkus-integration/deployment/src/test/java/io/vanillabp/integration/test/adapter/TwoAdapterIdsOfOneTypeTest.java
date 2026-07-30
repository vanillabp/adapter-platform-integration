package io.vanillabp.integration.test.adapter;

import java.util.List;
import java.util.Set;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.samples.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Acceptance test of the per-adapter-id bean convention on Quarkus
 * (adapter-config-model story 26d) - the structural foundation of the migration
 * scenario: TWO adapter ids of ONE type boot together. Id 'test' is served by an
 * <i>element</i> bean ({@link TestMigratableProcessService}), id 'test2' by a bean of
 * type <code>List&lt;MigratableProcessService&gt;</code>
 * ({@link Test2ListProcessServiceProducer}) - the platform's collection point
 * flattens List beans alongside element beans, and the election's fail-fast would
 * abort the boot if any prioritized id were unserved.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterIdsOfOneTypeTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // two adapter ids of the type 'dummy'; the module prioritizes 'test2'
          .addAsResource("two-adapter-ids/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
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

    // both configured ids of the dummy type are known and served (the election's
    // fail-fast in MigrationProcessService did not fire although 'test2' is only
    // provided via a flattened List bean)
    Assertions.assertEquals(
        Set.of("test", "test2"),
        migrationProcessService.getAdapters().keySet());

    // the workflow module prioritizes id 'test2' - new workflows start there
    Assertions.assertEquals(
        List.of("test2", "test"),
        migrationProcessService.getPrioritizedAdapters());

  }

}
