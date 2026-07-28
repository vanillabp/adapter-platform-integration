package io.vanillabp.integration.test.adapter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Regression test for the reachability of the guiding message "No adapters found in
 * classpath!": the adapter announcements are collected via an
 * {@code ObjectProvider} (not a required {@code List}), so booting WITHOUT any
 * adapter dependency reaches the transformer and its guiding message instead of
 * failing on unsatisfied injection.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NoAdapterBootTest {

  @Test
  public void bootWithoutAnyAdapterYieldsGuidingMessage() throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml")
        .hideResource("META-INF/workflow-module")
        .build()) {

      // note: the dummy adapter's configuration classes are NOT part of the context
      final var exception = Assertions.assertThrows(
          Exception.class,
          () -> testApp.applicationBuilder(
              WorkflowModuleAutoConfiguration.class,
              SpringBootMigrationAdapterAutoConfiguration.class,
              WorkflowModuleConfiguration.class)
              .run()
              .close());

      final var stringWriter = new StringWriter();
      exception.printStackTrace(new PrintWriter(stringWriter));
      Assertions.assertTrue(
          stringWriter.toString()
              .contains("No adapters found in classpath! Add dependencies providing VanillaBP adapters."),
          "expected the guiding message but got: "
              + stringWriter);

    }

  }

}
