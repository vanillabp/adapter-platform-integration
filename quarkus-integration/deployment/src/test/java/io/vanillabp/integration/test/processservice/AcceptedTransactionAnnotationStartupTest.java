package io.vanillabp.integration.test.processservice;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.samples.transactionalaccepted.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * The counterpart of {@link TransactionAnnotationStartupTest}: excluding VanillaBP's
 * {@code TaskException} from the rollback rules keeps the contract intact, so the
 * application boots with the annotation a VanillaBP 1 application carries.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AcceptedTransactionAnnotationStartupTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.transactionalaccepted")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  ProcessService<Aggregate> processService;

  @Test
  public void theApplicationBootsAndTheProcessServiceExists() {

    Assertions.assertNotNull(processService);

  }

}
