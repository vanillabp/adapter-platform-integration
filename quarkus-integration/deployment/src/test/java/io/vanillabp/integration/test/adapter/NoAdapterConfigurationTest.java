package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A CUSTOM adapter id (an id which is not an adapter type) can never be derived:
 * it carries no information about the adapter type it belongs to. Naming it in
 * <code>prioritized-adapters</code> without a section therefore still fails the
 * boot - with a message naming the property to add.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NoAdapterConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("no-adapter/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              There are VanillaBP adapters referenced not found in any property section 'vanillabp.adapters.*':
                vanillabp.prioritized-adapters => unknown
              """));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to the expected build exception
  }

}
