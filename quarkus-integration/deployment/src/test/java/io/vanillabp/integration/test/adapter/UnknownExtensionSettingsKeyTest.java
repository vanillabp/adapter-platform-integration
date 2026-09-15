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
 * What a plug-in setting written at a position nothing binds does on Quarkus: it ends the
 * startup naming the key, rather than being read by nobody.
 * <p>
 * The section here is <code>extension</code> where the binding declares
 * <code>extensions</code>, below an adapter - which is one of the eight positions
 * VanillaBP resolves (decision 53 in the repository's DECISIONS.md). So this test says
 * two things at once: a typo in a plug-in's settings is reported, and every position the
 * resolution offers has to be part of a registered mapping, because on Quarkus a position
 * which is not bound is not a position an application can write.
 * <p>
 * Spring Boot has no unknown-key detection, so there is no counterpart there; the
 * asymmetry is accepted (see {@code UnknownPropertyKeyConfigurationTest}).
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnknownExtensionSettingsKeyTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")
          .addAsResource("unknown-extension-settings-key/application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)
          .addClass(DummyAdapters.class)
          .addClass(TestMigratableProcessService.class))
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())
      .assertException(throwable -> {
        var message = "";
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          message += cause.getMessage()
              + "\n";
        }
        Assertions.assertTrue(
            message.contains("vanillabp.adapters.test.extension.sample.greeting"),
            "expected the unbound plug-in setting to be named but got:\n"
                + message);
      });

  @Test
  public void testUnknownExtensionSettingsKeyIsRejected() {
    // should never be executed due to the expected build exception
  }

}
