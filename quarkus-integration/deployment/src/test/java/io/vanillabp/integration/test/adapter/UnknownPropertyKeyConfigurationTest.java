package io.vanillabp.integration.test.adapter;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * With the blanket {@code withMappingIgnore("vanillabp.**")} gone, a typo inside the
 * {@code vanillabp.*} tree is detected again: every legitimate dynamic key is covered
 * by a registered {@code @ConfigMapping} (the platform's mapping plus the adapters'
 * overlay mappings - see the dummy adapter's {@code DummyAdapterOverlayProperties}),
 * so a key no mapping knows fails the startup instead of being silently ignored.
 * <p>
 * (Quarkus is deliberately stricter than Spring Boot here - the JavaBean binding on
 * Spring has no unknown-key detection; the asymmetry is accepted.)
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnknownPropertyKeyConfigurationTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // valid application properties plus one typo key inside the vanillabp tree
          .addAsResource("unknown-property-key/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      .assertException(throwable -> {
        var message = "";
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          message += cause.getMessage()
              + "\n";
        }
        // SmallRye's unknown-key validation names the offending key
        Assertions.assertTrue(
            message.contains("vanillabp.adapters.test.rest-adress"),
            "expected the unknown key to be named but got:\n"
                + message);
      });

  @Test
  public void testUnknownPropertyKeyIsRejected() {
    // should never be executed due to the expected build exception
  }

}
