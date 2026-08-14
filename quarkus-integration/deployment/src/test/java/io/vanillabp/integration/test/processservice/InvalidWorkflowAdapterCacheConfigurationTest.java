package io.vanillabp.integration.test.processservice;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.adapter.DummyAdapters;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentService;
import io.vanillabp.integration.test.adapter.TestAdapterDeploymentServiceProducer;
import io.vanillabp.integration.test.adapter.TestMigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Same-config-same-outcome matrix (core validation on all platforms): a cache which
 * cannot hold a single entry is rejected at startup with a guiding message.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InvalidWorkflowAdapterCacheConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("workflow-adapter-cache-invalid/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestAdapterDeploymentService.class)
          .addClass(TestAdapterDeploymentServiceProducer.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(
          exceptionHavingMessage(
              IllegalStateException.class,
              """
                  The property 'vanillabp.workflow-adapter-cache.max-entries' is 0 but has to be at least 1! \
                  The election cache is bounded on purpose (a full cache of the default 10000 entries costs about \
                  3 MB of heap). Remove the property to use the default or set the number of workflows the \
                  application keeps hot."""));

  @Test
  public void testInvalidBound() {
    // should never be executed due to the expected startup exception
  }

}
