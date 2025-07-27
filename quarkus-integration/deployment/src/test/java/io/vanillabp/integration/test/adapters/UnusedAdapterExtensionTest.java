package io.vanillabp.integration.test.adapters;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class UnusedAdapterExtensionTest {

  // Start unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("application.yaml")
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.twoDummyAdapters())       // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class, """
          No 'vanillabp.adapters.*' properties sections having types provided by Quarkus extension!
          Add section section if intended or remove extensions for these types: dummy2."""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to expected build exception
  }

}
