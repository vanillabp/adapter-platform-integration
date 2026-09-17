package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;

/**
 * The four places a workflow module may use do not rank against each other, so the same
 * file may lie in one of them only. A module which ships it twice ends the boot instead of
 * letting one of the two win (see decision 65 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheSameFileInTwoPlacesTest {

  private static final byte[] FILE = """
      test-module:
        a-setting: from-the-file
      """.getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("Both places are named and the boot ends")
  public void aFileShippedTwiceEndsTheBoot() throws Exception {

    try (final var testApplication = SpringBootTestApplication
        .builder()
        .addResource("config/test-module.yaml", FILE)
        .addResource("test-module/test-module.yaml", FILE)
        .build()) {
      final var failure = assertThrows(
          IllegalStateException.class,
          () -> testApplication
              .applicationBuilder(TestApplication.class)
              .run());
      assertTrue(
          failure.getMessage().contains("'config/test-module.yaml'") && failure.getMessage()
              .contains("'test-module/test-module.yaml'"),
          "the two places are not named: %s".formatted(failure.getMessage()));
    }

  }

}
