package io.vanillabp.integration.test.adapters;

import static io.vanillabp.integration.test.utils.AssertException.exceptionHavingMessage;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;

public class UnknownAdapterConfigurationTest {

  // Start unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // load sample application properties
          .addAsResource("unknown-adapter/application.yaml", "application.yaml")
          .addClass(DummyAdapters.class))                              // necessary due to anonymous class in DummyAdapters
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(exceptionHavingMessage(IllegalStateException.class, """
          Properties 'vanillabp.adapters.*.type' must contain VanillaBP adapters added as Quarkus extension!
          These adapters are unknown:
            'unknown' found in 'vanillabp.adapters.test.type'
          Available adapter types provided by Quarkus extensions currently loaded: dummy."""));

  @Test
  public void testAdapterConfiguration() {
    // should never be executed due to expected build exception
  }

}
