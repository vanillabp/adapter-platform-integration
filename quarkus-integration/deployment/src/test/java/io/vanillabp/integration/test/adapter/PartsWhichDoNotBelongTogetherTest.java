package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessageContaining;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application whose VanillaBP parts were never built together is stopped while Quarkus
 * builds it, which is earlier than any startup can be. What the test pins is the text the
 * build ends with: both versions and the dependency to change.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PartsWhichDoNotBelongTogetherTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addAsResource("adapter-from-the-future/META-INF/vanillabp/adapter-from-the-future.properties",
              "META-INF/vanillabp/adapter-from-the-future.properties")
          .addClass(DummyAdapters.class)
          .addClass(io.vanillabp.integration.test.adapter.TestPhaseTwoOutbox.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(exceptionHavingMessageContaining(IllegalStateException.class,
          "The VanillaBP adapter 'from-the-future' 9.9.9 was built against the VanillaBP platform integration 99.0.0",
          "so this application does not start",
          "io.vanillabp:vanillabp-bom",
          "io.vanillabp.test:adapter-from-the-future"));

  @Test
  public void aBuildPuttingPartsTogetherWhichDoNotBelongTogetherFails() {
    // never runs: the build ends before the application does
  }

}
