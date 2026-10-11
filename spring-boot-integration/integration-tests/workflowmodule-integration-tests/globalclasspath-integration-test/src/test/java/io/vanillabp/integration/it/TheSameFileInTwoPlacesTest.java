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
 * The four places a workflow module may use have no ranking among each other. So a file
 * may lie in only one of them. If a module ships it twice, the boot ends. Neither of the
 * two copies wins (see decision 65 in the repository's DECISIONS.md).
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
