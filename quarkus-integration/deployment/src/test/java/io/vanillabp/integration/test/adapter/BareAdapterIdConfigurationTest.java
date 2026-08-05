package io.vanillabp.integration.test.adapter;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterTransformer;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter section consisting of nothing but the adapter id relies on the
 * convention "the adapter id IS the adapter type". It configures the adapter on
 * Spring Boot, but NOT on Quarkus: the configuration binding derives the keys of a
 * properties map from the property NAMES below its prefix, and a section without any
 * property contributes no name (spelling it as an empty-valued property is rejected
 * by SmallRye with "does not map to any root"). The id therefore never reaches the
 * runtime and the situation cannot be detected - so the guiding message names the
 * behavior, which is what this test pins.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BareAdapterIdConfigurationTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("bare-adapter-id/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              No adapters configured! Add a properties section for each BPMS used, having 'type' set to an adapter type found in classpath:
                vanillabp.adapters.dummy.type=dummy

              Hint: naming the adapter id after the adapter type makes 'type' unnecessary (e.g. 'vanillabp.adapters.dummy').
              """ + QuarkusMigrationAdapterTransformer.QUARKUS_EMPTY_SECTION_NOTE));

  @Test
  public void testBareAdapterIdIsReportedGuiding() {
    // should never be executed due to the expected build exception
  }

}
