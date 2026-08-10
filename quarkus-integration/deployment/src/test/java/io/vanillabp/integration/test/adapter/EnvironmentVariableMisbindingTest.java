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
 * Environment variables cannot INTRODUCE a new adapter id (only override entries
 * declared in a configuration file) - the binder cannot reconstruct a dashed/dotted
 * id from the variable's name. Instead of silently ignoring such a variable, the
 * startup fails with a guiding message (core validation
 * {@code MigrationAdapterProperties.validateEnvironmentVariableUsage}, fed with the
 * raw property names which include unconverted environment-variable-shaped keys).
 */
@ExtendWith(SuppressOutputExtension.class)
public class EnvironmentVariableMisbindingTest {

  // Start the unit test with the extension loaded, and sample classes
  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addPackage("io.vanillabp.integration.test.samples.sample")  // load sample application classes
          // valid application properties (adapter id 'test')
          .addAsResource("application.yaml", "application.yaml")
          .addAsResource("workflow-module-descriptor/workflow-module", WorkflowModule.METAINF_WORKFLOWMODULE)           // define workflow module at global classpath
          .addClass(DummyAdapters.class)                              // necessary due to anonymous class in DummyAdapters
          .addClass(TestMigratableProcessService.class))            // process service of the mocked adapter
      .addBuildChainCustomizer(DummyAdapters.oneDummyAdapter())     // add mocked adapter
      // an environment-variable shaped key addressing an id which is NOT declared
      // in any configuration file (surfaces in the raw property names exactly like
      // a real environment variable does)
      .overrideConfigKey("VANILLABP_ADAPTERS_PHANTOM_ID_TYPE", "dummy")
      .assertException(throwable -> {
        var message = "";
        for (var cause = throwable; cause != null; cause = cause.getCause()) {
          message += cause.getMessage()
              + "\n";
        }
        Assertions.assertTrue(
            message.contains("VANILLABP_ADAPTERS_PHANTOM_ID_TYPE"),
            "expected the variable to be named but got:\n"
                + message);
        Assertions.assertTrue(
            message.contains("cannot introduce a new adapter or workflow module"),
            "expected the guiding explanation but got:\n"
                + message);
        Assertions.assertTrue(
            message.contains("'test'"),
            "expected the configured ids to be named but got:\n"
                + message);
      });

  @Test
  public void testEnvironmentVariableMisbindingIsRejected() {
    // should never be executed due to the expected build exception
  }

}
