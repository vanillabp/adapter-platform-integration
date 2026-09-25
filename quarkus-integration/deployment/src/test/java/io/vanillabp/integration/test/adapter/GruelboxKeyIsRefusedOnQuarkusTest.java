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
 * An application which brings a Spring Boot setting to Quarkus. The gruelbox store is built
 * on Spring Boot alone, so the key is right in itself and wrong here, and SmallRye would
 * answer it with <code>SRCFG00050</code> and the name of the key - true, and no help at all
 * to somebody who just moved their configuration over.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxKeyIsRefusedOnQuarkusTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // a valid configuration plus the switch of the store Spring Boot alone builds
          .addAsResource("gruelbox-on-quarkus/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              These keys configure the gruelbox outbox store, and Quarkus does not build that store:
                vanillabp.outbox.gruelbox.enabled
              It runs on Spring Boot alone, because it needs the Spring transaction manager gruelbox is written against. Remove the keys and let VanillaBP store the phase-two entries itself: it writes them into the table of 'vanillabp.outbox.jdbc.*' where the application has a data source, and into the collection of 'vanillabp.outbox.mongo.*' where it has MongoDB."""));

  @Test
  public void testGruelboxKeyIsRefused() {
    // should never be executed due to the expected build exception
  }

}
