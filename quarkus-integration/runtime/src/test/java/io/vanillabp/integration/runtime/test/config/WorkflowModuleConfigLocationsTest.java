package io.vanillabp.integration.runtime.test.config;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificPropertiesConfigSourceProvider;
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigSourceProvider;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Each of the four places a workflow module may put its configuration file, asked of the
 * config source providers themselves. Spring Boot reads the same four, so a workflow
 * module keeps its style whichever platform it ends up on.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleConfigLocationsTest {

  private static final int ORDINAL = 235;

  private static final String MODULE = "measured-module";

  @TempDir
  java.nio.file.Path classpath;

  @Test
  @DisplayName("A file at the classpath root is read")
  public void aFileAtTheClasspathRootIsRead() throws Exception {

    Assertions.assertEquals("from-the-file", valueOfAFileAt("measured-module.yaml"));

  }

  @Test
  @DisplayName("A file in 'config/' is read")
  public void aFileInTheConfigDirectoryIsRead() throws Exception {

    Assertions.assertEquals("from-the-file", valueOfAFileAt("config/measured-module.yaml"));

  }

  @Test
  @DisplayName("A file in the module's own directory is read")
  public void aFileInTheModulesDirectoryIsRead() throws Exception {

    Assertions.assertEquals(
        "from-the-file",
        valueOfAFileAt("measured-module/measured-module.yaml"));

  }

  @Test
  @DisplayName("A file in 'config/' of the module's own directory is read")
  public void aFileInTheModulesConfigDirectoryIsRead() throws Exception {

    Assertions.assertEquals(
        "from-the-file",
        valueOfAFileAt("measured-module/config/measured-module.yaml"));

  }

  @Test
  @DisplayName("A properties file is read at the same places")
  public void aPropertiesFileIsReadAtTheSamePlaces() throws Exception {

    write("config/measured-module.properties", "measured-module.a-setting=from-the-file\n");

    try (final var classLoader = classLoaderOfTheModule()) {
      Assertions.assertEquals(
          "from-the-file",
          new SmallRyeConfigBuilder()
              .forClassLoader(classLoader)
              .withSources(new WorkflowModuleSpecificPropertiesConfigSourceProvider(MODULE, ORDINAL))
              .build()
              .getValue("measured-module.a-setting", String.class));
    }

  }

  private String valueOfAFileAt(
      final String location) throws Exception {

    write(location, """
        measured-module:
          a-setting: from-the-file
        """);

    try (final var classLoader = classLoaderOfTheModule()) {
      return new SmallRyeConfigBuilder()
          .forClassLoader(classLoader)
          .withSources(new WorkflowModuleSpecificYamlConfigSourceProvider(MODULE, ORDINAL))
          .build()
          .getValue("measured-module.a-setting", String.class);
    }

  }

  private void write(
      final String location,
      final String content) throws Exception {

    final var file = classpath.resolve(location);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);

  }

  private URLClassLoader classLoaderOfTheModule() throws Exception {

    return new URLClassLoader(new URL[]{
        classpath.toUri().toURL()
    }, null);

  }

}
