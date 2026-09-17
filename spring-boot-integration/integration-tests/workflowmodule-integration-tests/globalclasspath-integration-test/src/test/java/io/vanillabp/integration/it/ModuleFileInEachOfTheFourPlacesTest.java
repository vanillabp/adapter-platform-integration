package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.TestApplication;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;

/**
 * A workflow module may put its configuration file at the classpath root, in a
 * <i>config</i> directory, in a directory named after the module, or in a <i>config</i>
 * directory of that one. Quarkus reads the same four, so a module keeps its style
 * whichever platform it runs on (see decision 65 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModuleFileInEachOfTheFourPlacesTest {

  private static final byte[] FILE = """
      test-module:
        a-setting: from-the-file
      """.getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("A file at the classpath root is read")
  public void aFileAtTheClasspathRootIsRead() throws Exception {

    assertEquals("from-the-file", valueOfAModuleShipping("test-module.yaml"));

  }

  @Test
  @DisplayName("A file in 'config/' is read")
  public void aFileInTheConfigDirectoryIsRead() throws Exception {

    assertEquals("from-the-file", valueOfAModuleShipping("config/test-module.yaml"));

  }

  @Test
  @DisplayName("A file in the module's own directory is read")
  public void aFileInTheModulesDirectoryIsRead() throws Exception {

    assertEquals("from-the-file", valueOfAModuleShipping("test-module/test-module.yaml"));

  }

  @Test
  @DisplayName("A file in 'config/' of the module's own directory is read")
  public void aFileInTheModulesConfigDirectoryIsRead() throws Exception {

    assertEquals("from-the-file", valueOfAModuleShipping("test-module/config/test-module.yaml"));

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
        return context.getEnvironment().getProperty("test-module.a-setting");
      } finally {
        context.close();
      }
    }

  }

}
