package io.vanillabp.integration.test.adapter;

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
 * Regression test for review finding B1 on Quarkus: two different adapter TYPES
 * ('dummy' serving adapter id 'test' and 'dummy2' serving 'test2') coexist in one
 * application. Both process services are found by the election (the election fails
 * fast on any prioritized adapter without a process service, so resolving the
 * process service proves both are served) and the priorities follow the
 * configuration. (Deployment services are a Spring-only concern until the Quarkus
 * deployment pipeline lands - story 26b.)
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterTypesConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("two-adapter-types/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                           // necessary due to anonymous class in DummyAdapters
          .addClass(TestAdapterDeploymentService.class) // deployment service required per prioritized adapter
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)             // process service of the first adapter type
          .addClass(Test2MigratableProcessService.class))           // process service of the second adapter type
      .addBuildChainCustomizer(DummyAdapters.twoDummyAdapters()); // add both mocked adapter types

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> sampleProcessService;

  @Test
  public void testTwoAdapterTypesCoexist() {

    Assertions.assertNotNull(sampleProcessService);
    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, sampleProcessService);

    final var processServiceBean = ((ProcessServiceBaseCdiBean<Aggregate>) sampleProcessService);
    final var migrationProcessService = processServiceBean.getMigrationProcessService();

    // both adapter types are configured and both prioritized ids are served -
    // otherwise the election's fail-fast would have thrown on bean creation
    final var adaptersConfigured = migrationProcessService.getAdapters();
    Assertions.assertEquals(2, adaptersConfigured.size());
    Assertions.assertEquals("dummy", adaptersConfigured.get("test"));
    Assertions.assertEquals("dummy2", adaptersConfigured.get("test2"));

    final var prioritizedAdapters = migrationProcessService.getPrioritizedAdapters();
    Assertions.assertEquals(2, prioritizedAdapters.size());
    Assertions.assertEquals("test", prioritizedAdapters.getFirst());
    Assertions.assertEquals("test2", prioritizedAdapters.getLast());

  }

}
