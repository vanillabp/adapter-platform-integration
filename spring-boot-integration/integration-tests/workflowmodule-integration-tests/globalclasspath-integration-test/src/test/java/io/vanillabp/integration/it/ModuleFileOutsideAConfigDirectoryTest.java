package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;

/**
 * The counterpart of {@code ModuleFileInAConfigDirectoryTest}: a workflow module whose file
 * lies where both platforms read it says nothing at startup. It boots on its own because the
 * captured output of a test class holds what every test of it wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModuleFileOutsideAConfigDirectoryTest {

  private static final byte[] FILE = """
      test-module:
        a-setting: from-the-file
      """.getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("A module whose file lies where both platforms read it is not reported")
  public void aFileOutsideAConfigDirectoryIsNotReported(
      final CapturedOutput output) throws Exception {

    try (final var testApplication = SpringBootTestApplication
        .builder()
        .addResource("test-module/test-module.yaml", FILE)
        .build()) {
      final var context = testApplication
          .applicationBuilder(TestApplication.class)
          .run();
      try {
        assertEquals(
            "from-the-file",
            context.getEnvironment().getProperty("test-module.a-setting"));
      } finally {
        context.close();
      }
    }

    assertFalse(
        output.getAll().contains("which belongs at"),
        "a module which works on both platforms is reported all the same");

  }

}
