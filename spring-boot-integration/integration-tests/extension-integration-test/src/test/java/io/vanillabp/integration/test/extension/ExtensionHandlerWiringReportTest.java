package io.vanillabp.integration.test.extension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the handler methods of an extension. It says which method serves
 * which key of which BPMN process. The line also says which method runs where no key is
 * served. No method of this application serves every element, so only that sentence is
 * checked, not a method name. The <code>&#64;WorkflowTask</code> side of a workflow module is reported in the same
 * way. Before this report, a developer whose extension method was never called had
 * nothing to read.
 * <p>
 * The test starts the application INSIDE the test method. The report is written while
 * the workflow module is deployed. A context booted before the test would have written it
 * where nothing captures the output.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerWiringReportTest {

  @Test
  @DisplayName("The boot names the method serving each key")
  public void theWiringOfTheExtensionIsReported(
      final CapturedOutput output) {

    try (var context = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        // its own database: the other contexts of this scenario are still cached, and the
        // schema must not be created twice
        .properties("spring.datasource.url=jdbc:h2:mem:extension-wiring-report;DB_CLOSE_DELAY=-1")
        .run()) {

      // Everything read below is the output of this test alone. Every boot of this
      // scenario writes a wiring report, and the sentence which must be absent belongs to
      // it. So a second test booting here would decide whether this one passes.
      final var reported = output.getAllOfThisTest();
      Assertions.assertTrue(
          reported.contains("Extension 'sample' serves BPMN process 'DummyProcess'"),
          "no report about what the extension was wired to: "
              + reported);
      Assertions.assertTrue(reported.contains("extension-module"), reported);
      Assertions.assertTrue(reported.contains("noteOfTheUserTaskByElementId"), reported);
      Assertions.assertTrue(reported.contains("'%s'".formatted(TestApplication.USER_TASK_ID)), reported);
      Assertions.assertTrue(reported.contains("noteOfTheUserTaskByTaskDefinition"), reported);
      Assertions
          .assertTrue(reported.contains("'%s'".formatted(TestApplication.USER_TASK_DEFINITION)), reported);
      Assertions.assertTrue(reported.contains("the first one a method serves wins"), reported);
      // the BPMN process whose workflow service knows nothing about the extension is not
      // named. A line per pair of extension and process would say nothing for most pairs
      Assertions.assertFalse(
          reported.contains("serves BPMN process 'UnnotedProcess'"),
          reported);

    }

  }

}
