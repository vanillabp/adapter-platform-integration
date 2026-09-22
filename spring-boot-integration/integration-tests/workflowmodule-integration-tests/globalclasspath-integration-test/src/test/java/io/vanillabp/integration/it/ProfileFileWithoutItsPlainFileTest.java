package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * A workflow module which ships <i>test-module-tenant.yaml</i> and no
 * <i>test-module.yaml</i>. Spring Boot reads such a file, Quarkus does not, and the
 * workflow module is the same library on both. So the values are there and the boot says
 * what a Quarkus application would make of this module (see decision 61 in the
 * repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProfileFileWithoutItsPlainFileTest {

  private static final byte[] PROFILE_FILE = """
      test-module:
        profile-only: from-the-modules-profile-file
      """.getBytes(StandardCharsets.UTF_8);

  private static final byte[] PLAIN_FILE = """
      test-module:
        plain-only: from-the-modules-plain-file
      """.getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("The values are read and the boot names the file a Quarkus application would skip")
  public void theProfileFileIsReadAndReported(
      final CapturedOutput output) throws Exception {

    try (final var testApplication = SpringBootTestApplication
        .builder()
        .addResource("test-module-tenant.yaml", PROFILE_FILE)
        .build()) {
      final var context = testApplication
          .applicationBuilder(TestApplication.class)
          .profiles("tenant")
          .run();
      try {
        assertEquals(
            "from-the-modules-profile-file",
            context.getEnvironment().getProperty("test-module.profile-only"));
      } finally {
        context.close();
      }
    }

    assertTrue(
        output.getAll().contains("test-module-tenant.yaml") && output.getAll().contains("'test-module.yaml'") && output
            .getAll().contains("An empty file is enough"),
        "the boot does not name the file nor the file which would make Quarkus read it");

  }

  @Test
  @DisplayName("A module whose plain file lies next to the profile file is not reported")
  public void aModuleWithBothFilesIsNotReported(
      final CapturedOutput output) throws Exception {

    try (final var testApplication = SpringBootTestApplication
        .builder()
        .addResource("test-module-tenant.yaml", PROFILE_FILE)
        .addResource("test-module.yaml", PLAIN_FILE)
        .build()) {
      final var context = testApplication
          .applicationBuilder(TestApplication.class)
          .profiles("tenant")
          .run();
      try {
        assertEquals(
            "from-the-modules-profile-file",
            context.getEnvironment().getProperty("test-module.profile-only"));
        assertEquals(
            "from-the-modules-plain-file",
            context.getEnvironment().getProperty("test-module.plain-only"));
      } finally {
        context.close();
      }
    }

    assertFalse(
        output.getAllOfThisTest().contains("test-module-tenant.yaml"),
        "a module which works on both platforms is reported all the same");

  }

}
