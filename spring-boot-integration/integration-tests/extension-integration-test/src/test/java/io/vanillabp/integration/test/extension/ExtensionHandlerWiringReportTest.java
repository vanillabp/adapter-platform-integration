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
 * What a boot says about the handler methods of an extension: which method serves which
 * key of which BPMN process, and which of them runs where no key is served at all. The
 * <code>&#64;WorkflowTask</code> side of a workflow module is reported this way, and a
 * developer whose method of an extension is never called had nothing to read before.
 * <p>
 * The application is started INSIDE the test, because the report is written while the
 * workflow module is deployed and a context booted before the test began would have
 * written it where nothing captures output.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerWiringReportTest {

  @Test
  @DisplayName("The boot names the method serving each key and the one serving every element")
  public void theWiringOfTheExtensionIsReported(
      final CapturedOutput output) {

    try (var context = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        // a database of its own: the scenario's other contexts are still cached and the
        // schema must not be created twice
        .properties("spring.datasource.url=jdbc:h2:mem:extension-wiring-report;DB_CLOSE_DELAY=-1")
        .run()) {

      final var reported = output.getAll();
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
      // named: a line per pair of extension and process would be a line about nothing for
      // most of them
      Assertions.assertFalse(
          reported.contains("serves BPMN process 'UnnotedProcess'"),
          reported);

    }

  }

}
