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
 * An application which brings a Spring Boot setting to Quarkus. The gruelbox store only
 * runs on Spring Boot. So the key is right in itself, but wrong here. SmallRye would answer
 * it with <code>SRCFG00050</code> and the name of the key. That is true, but it does not
 * help somebody who just moved their configuration over. So the message also says where
 * that store lives now. A reader who set the key asked for something which VanillaBP does
 * not carry any more.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxKeyIsRefusedOnQuarkusTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // a valid configuration, plus the switch of the store which only Spring Boot builds
          .addAsResource("gruelbox-on-quarkus/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              These keys configure the gruelbox outbox store, and no such store exists on Quarkus:
                vanillabp.outbox.gruelbox.enabled
              It runs on Spring Boot alone, because it needs the Spring transaction manager gruelbox is written against, and it is not part of VanillaBP at all - the artifact 'io.vanillabp:gruelbox-phase-two-outbox' carries it. Remove the keys and let VanillaBP store the phase-two entries itself: it writes them into the table of 'vanillabp.outbox.jdbc.*' where the application has a data source, and into the collection of 'vanillabp.outbox.mongo.*' where it has MongoDB."""));

  @Test
  public void testGruelboxKeyIsRefused() {
    // should never be executed due to the expected build exception
  }

}
