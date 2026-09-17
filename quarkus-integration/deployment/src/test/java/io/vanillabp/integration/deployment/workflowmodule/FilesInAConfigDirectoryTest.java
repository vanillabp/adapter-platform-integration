package io.vanillabp.integration.deployment.workflowmodule;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the build says about a workflow module which ships its file in a
 * <i>config</i> directory. That neither of those two places is read here is measured by
 * {@code ModuleFileInAConfigDirectoryTest} and
 * {@code ModuleFileInTheModulesConfigDirectoryTest} of the deployment integration tests;
 * what is asked here is which files the report picks and what it offers the developer.
 */
@ExtendWith(SuppressOutputExtension.class)
public class FilesInAConfigDirectoryTest {

  @Test
  @DisplayName("A file at the classpath root belongs one directory up")
  public void aFileInTheConfigDirectoryIsReported() {

    final var warning = WorkflowModuleBuildStepProcessor
        .messageAboutFilesInAConfigDirectory(List.of("config/loan-approval.yaml"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("config/loan-approval.yaml"),
        "the file is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("which belongs at 'loan-approval.yaml'"),
        "the place it belongs at is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("Spring Boot"),
        "the platform which does read the file is not named: %s".formatted(warning));

  }

  @Test
  @DisplayName("A file inside the module's directory keeps that directory")
  public void aFileInTheModulesConfigDirectoryIsReported() {

    final var warning = WorkflowModuleBuildStepProcessor
        .messageAboutFilesInAConfigDirectory(List.of("loan-approval/config/loan-approval-prod.properties"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("which belongs at 'loan-approval/loan-approval-prod.properties'"),
        "the place it belongs at is not named: %s".formatted(warning));

  }

  @Test
  @DisplayName("Nothing is said where no module put a file there")
  public void aModuleWithoutSuchAFileIsNotReported() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutFilesInAConfigDirectory(Set.of())
            .isEmpty(),
        "a warning was written although no file lies in a config directory");

  }

}
