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
 * A method with the annotation of the extension which is not public. So VanillaBP does
 * not wire it. For a <code>&#64;WorkflowTask</code>, the mistake shows up later as a task
 * nobody serves. For an extension it never shows up. The extension asks whether a method
 * exists, hears no, and does something else instead. So the boot names the method, its
 * class and the way out.
 * <p>
 * The test starts the application INSIDE the test method. The report is written while
 * the workflow services are scanned. A context booted before the test would have written
 * it where nothing captures the output.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionHandlerMethodsNobodySeesTest {

  @Test
  @DisplayName("A method of the extension's annotation which is not public is named while booting")
  public void theInvisibleExtensionHandlerIsReported(
      final CapturedOutput output) {

    try (var context = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        // its own database: the other context of this scenario is still cached, and its
        // schema must not be created twice
        .properties("spring.datasource.url=jdbc:h2:mem:extension-nobody-sees;DB_CLOSE_DELAY=-1")
        .run()) {

      final var reported = output.getAll();
      Assertions.assertTrue(
          reported.contains("which VanillaBP does not see"),
          "no report about the handler methods nobody sees: "
              + reported);
      Assertions.assertTrue(reported.contains(NotedWorkflowService.class.getName()), reported);
      Assertions.assertTrue(reported.contains("@SampleNote"), reported);
      Assertions.assertTrue(reported.contains("noteNobodyReaches"), reported);
      Assertions.assertTrue(reported.contains("is protected"), reported);
      Assertions.assertTrue(reported.contains("Make the method public"), reported);

    }

  }

}
