package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;

/**
 * A workflow module which ships its file in a <i>config</i> directory. Spring Boot reads
 * both of those places, Quarkus reads neither, and the workflow module is the same library
 * on both. So the values are there and the boot says where the file belongs if the module
 * is to work on either platform (see decision 65 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModuleFileInAConfigDirectoryTest {

  private static final byte[] FILE = """
      test-module:
        from-a-config-directory: it-is-read
      """.getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("A file in 'config/' is read and the boot names where it belongs")
  public void aFileInTheConfigDirectoryIsReadAndReported(
      final CapturedOutput output) throws Exception {

    assertEquals(
        "it-is-read",
        valueOfAModuleShipping("config/test-module.yaml"));

    assertTrue(
        output.getAll().contains("config/test-module.yaml") && output.getAll()
            .contains("which belongs at 'test-module.yaml'"),
        "the boot does not name the file nor the place it belongs at");

  }

  @Test
  @DisplayName("A file in '<module-id>/config/' is read and the boot names where it belongs")
  public void aFileInTheModulesConfigDirectoryIsReadAndReported(
      final CapturedOutput output) throws Exception {

    assertEquals(
        "it-is-read",
        valueOfAModuleShipping("test-module/config/test-module.yaml"));

    assertTrue(
        output.getAll().contains("which belongs at 'test-module/test-module.yaml'"),
        "the boot does not name the place the file belongs at");

  }

  private static String valueOfAModuleShipping(
      final String location) throws Exception {

    try (final var testApplication = SpringBootTestApplication
        .builder()
        .addResource(location, FILE)
        .build()) {
      final var context = testApplication
          .applicationBuilder(TestApplication.class)
          .run();
      try {
        return context.getEnvironment().getProperty("test-module.from-a-config-directory");
      } finally {
        context.close();
      }
    }

  }

}
