package io.vanillabp.integration.deployment.config;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a native build says about the profile-specific configuration files of the application.
 * The build step needs a native build to run at all, so what it says is tested here, where the
 * files and the profiles are handed in.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProfileSpecificApplicationFilesTest {

  @Test
  public void testAFileOfAnotherProfileIsReportedWithEveryWayOutOfIt() {

    final var warning = ProfileSpecificApplicationFilesBuildStepProcessor
        .messageAboutFilesTheImageWillNotHonor(
            List.of("application-tenant.yaml"),
            List.of("prod"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("application-tenant.yaml"),
        "the file is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("'%tenant:'"),
        "the profile section of 'application.yaml' is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("-Dquarkus.profile=tenant"),
        "building one image per profile is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("quarkus.native.resources.includes=application-*.yaml"),
        "embedding the file is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("-Dsmallrye.config.locations=application-tenant.yaml"),
        "naming the file at the binary is not named: %s".formatted(warning));

  }

  @Test
  public void testTheProfileTheImageIsBuiltWithIsNotReported() {

    Assertions.assertTrue(
        ProfileSpecificApplicationFilesBuildStepProcessor
            .messageAboutFilesTheImageWillNotHonor(
                List.of("application-tenant.properties"),
                List.of("prod", "tenant"))
            .isEmpty());

  }

  @Test
  public void testTheProfilesABinaryIsNotMeantForAreNotReported() {

    Assertions.assertTrue(
        ProfileSpecificApplicationFilesBuildStepProcessor
            .messageAboutFilesTheImageWillNotHonor(
                List.of("application-dev.yaml", "application-test.yaml"),
                List.of("prod"))
            .isEmpty());

  }

  @Test
  public void testAnApplicationWithoutProfileSpecificFilesIsNotWarned() {

    Assertions.assertTrue(
        ProfileSpecificApplicationFilesBuildStepProcessor
            .messageAboutFilesTheImageWillNotHonor(
                List.of(),
                List.of("prod"))
            .isEmpty());

  }

  @Test
  public void testEveryFileIsNamedAndTheRemediesUseTheFirstOfThem() {

    final var warning = ProfileSpecificApplicationFilesBuildStepProcessor
        .messageAboutFilesTheImageWillNotHonor(
            List.of("application-staging.properties", "application-tenant.yaml"),
            List.of("prod"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("application-staging.properties") && warning.contains("application-tenant.yaml"),
        "not every file is named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("quarkus.native.resources.includes=application-*.properties"),
        "the remedies do not use the first file found: %s".formatted(warning));

  }

}
