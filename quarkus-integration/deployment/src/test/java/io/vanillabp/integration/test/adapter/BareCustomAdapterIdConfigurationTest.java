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
 * A CUSTOM adapter id can never be derived from the classpath (it carries no
 * information about its type), so the Quarkus binding limitation stays real for
 * it: a section consisting of nothing but <code>my-c7:</code> contributes no
 * property name and is dropped silently. With SEVERAL adapter types in the
 * classpath nothing can be derived either, so the boot fails - and the guiding
 * message names both the remedy and the Quarkus behavior which made the section
 * disappear.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BareCustomAdapterIdConfigurationTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          .addAsResource("bare-custom-adapter-id/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.twoDummyAdapters())    // add two mocked adapter types
      .assertException(exceptionHavingMessage(IllegalStateException.class,
          """
              Several VanillaBP adapters were found in classpath:
                dummy
                dummy2
              Name the order in which they are used by the property 'vanillabp.prioritized-adapters' - the first one starts new workflows, the others are asked for workflows started earlier (BPMS migration).
              Sample:
                vanillabp.prioritized-adapters:
                  - dummy
                  - dummy2
              An adapter id which IS an adapter type needs no further configuration; a custom id needs a section 'vanillabp.adapters.<id>.type=<adapter type>'.
              """ + QuarkusMigrationAdapterTransformer.QUARKUS_EMPTY_SECTION_NOTE));

  @Test
  public void testBareCustomAdapterIdIsReportedGuiding() {
    // should never be executed due to the expected build exception
  }

}
