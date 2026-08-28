package io.vanillabp.integration.test.adapter;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
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
 * The other half of {@link GuessingAdapterStartupTest}: an application which knowingly
 * wants its operations routed by list order says so
 * (<code>vanillabp.election.guessing-adapters: ACCEPTED</code>) and boots. What it gets
 * instead of the refusal is a WARN, so the decision stays visible in the log.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AcceptedGuessingAdapterStartupTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("guessing-adapter/accepted.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestPhaseTwoOutbox.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class)
          .addClass(Test2ListProcessServiceProducer.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter());

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<Aggregate> processService;

  @Test
  @DisplayName("An accepted guessing adapter boots, and both adapters stay prioritized")
  public void anAcceptedGuessingAdapterBoots() {

    Assertions.assertInstanceOf(ProcessServiceBaseCdiBean.class, processService);

    final var migrationProcessService = ((ProcessServiceBaseCdiBean<Aggregate>) processService)
        .getMigrationProcessService();
    Assertions.assertEquals(
        List.of("test", "test2"),
        migrationProcessService.getPrioritizedAdapters());

  }

}
