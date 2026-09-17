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
import io.vanillabp.integration.runtime.config.WorkflowModuleSpecificYamlConfigSourceProvider;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The rule the report of the build rests on, asked of SmallRye itself: a file named after
 * a profile is loaded where the file without the profile lies in the same place, and it is
 * not loaded anywhere else. Measured on 2026-09-17 against SmallRye 3.17.2, the version
 * Quarkus 3.39 brings. Should SmallRye ever pair the two differently, this test says so
 * first, and the report of
 * {@code WorkflowModuleBuildStepProcessor#reportProfileFilesWithoutTheirPlainFile} has
 * lost its reason.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleProfileFileNeedsItsPlainFileTest {

  private static final int ORDINAL = 235;

  @TempDir
  java.nio.file.Path classpath;

  @Test
  @DisplayName("A profile file lying next to its plain file is read")
  public void aProfileFileNextToItsPlainFileIsRead() throws Exception {

    Files.writeString(classpath.resolve("measured-module.yaml"), """
        measured-module:
          plain-only: from-the-plain-file
        """);
    Files.writeString(classpath.resolve("measured-module-tenant.yaml"), """
        measured-module:
          profile-only: from-the-profile-file
        """);

    try (final var classLoader = classLoaderOfTheModule()) {
      final var config = configOf(classLoader);
      Assertions.assertEquals(
          "from-the-plain-file",
          config.getValue("measured-module.plain-only", String.class));
      Assertions.assertEquals(
          "from-the-profile-file",
          config.getValue("measured-module.profile-only", String.class));
    }

  }

  @Test
  @DisplayName("A profile file on its own is read by nobody")
  public void aProfileFileOnItsOwnIsReadByNobody() throws Exception {

    Files.writeString(classpath.resolve("measured-module-tenant.yaml"), """
        measured-module:
          profile-only: from-the-profile-file
        """);

    try (final var classLoader = classLoaderOfTheModule()) {
      Assertions.assertTrue(
          configOf(classLoader)
              .getOptionalValue("measured-module.profile-only", String.class)
              .isEmpty(),
          "the file was read although the plain file it belongs to is missing");
    }

  }

  private URLClassLoader classLoaderOfTheModule() throws Exception {

    return new URLClassLoader(new URL[]{
        classpath.toUri().toURL()
    }, null);

  }

  private static io.smallrye.config.SmallRyeConfig configOf(
      final ClassLoader classLoader) {

    return new SmallRyeConfigBuilder()
        .forClassLoader(classLoader)
        .withProfile("tenant")
        .withSources(new WorkflowModuleSpecificYamlConfigSourceProvider("measured-module", ORDINAL))
        .build();

  }

}
