package io.vanillabp.integration.deployment.workflowmodule;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the build says about a workflow module which ships a file for a profile and not the
 * file the profile belongs to. The rule it reports is SmallRye's and is measured in
 * {@code WorkflowModuleProfileFileNeedsItsPlainFileTest} of the runtime module; what is
 * asked here is which files the report picks and what it offers the developer.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ProfileFilesWithoutTheirPlainFileTest {

  @Test
  @DisplayName("A profile file on its own is named with the file it needs and the way out")
  public void aProfileFileOnItsOwnIsReported() {

    final var warning = WorkflowModuleBuildStepProcessor
        .messageAboutProfileFilesWithoutTheirPlainFile(
            List.of(Set.of("loan-approval-prod.yaml")),
            List.of("loan-approval"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("loan-approval-prod.yaml"),
        "the file is not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("'loan-approval.yaml'") && warning.contains("'loan-approval.yml'"),
        "the files which would make it be read are not named: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("An empty file is enough"),
        "the smallest fix is not offered: %s".formatted(warning));
    Assertions.assertTrue(
        warning.contains("Spring Boot"),
        "the platform which does read the file is not named: %s".formatted(warning));

  }

  @Test
  @DisplayName("A profile file whose plain file lies next to it is not reported")
  public void aProfileFileWithItsPlainFileIsNotReported() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan-approval.yaml", "loan-approval-prod.yaml")),
                List.of("loan-approval"))
            .isEmpty());

  }

  @Test
  @DisplayName("The plain file may carry the other extension of the same provider")
  public void thePlainFileMayBeTheOtherExtensionOfTheSameProvider() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan-approval.yml", "loan-approval-prod.yaml")),
                List.of("loan-approval"))
            .isEmpty());

  }

  @Test
  @DisplayName("A plain file the other provider reads does not pair with the profile file")
  public void aPlainFileOfTheOtherProviderDoesNotHelp() {

    final var warning = WorkflowModuleBuildStepProcessor
        .messageAboutProfileFilesWithoutTheirPlainFile(
            List.of(Set.of("loan-approval.properties", "loan-approval-prod.yaml")),
            List.of("loan-approval"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("loan-approval-prod.yaml"),
        "the YAML file is not named although only a .properties file lies next to it: %s"
            .formatted(warning));
    Assertions.assertFalse(
        warning.contains("loan-approval-prod.properties"),
        "a file nobody reported shows up: %s".formatted(warning));

  }

  @Test
  @DisplayName("A plain file in another directory is not next to the profile file")
  public void aPlainFileInAnotherDirectoryIsNotNextToIt() {

    final var warning = WorkflowModuleBuildStepProcessor
        .messageAboutProfileFilesWithoutTheirPlainFile(
            List.of(Set.of("loan-approval.yaml", "loan-approval/loan-approval-prod.yaml")),
            List.of("loan-approval"))
        .orElseThrow();

    Assertions.assertTrue(
        warning.contains("loan-approval/loan-approval-prod.yaml") && warning
            .contains("'loan-approval/loan-approval.yaml'"),
        "the file in the subdirectory is not asked to get its own plain file: %s"
            .formatted(warning));

  }

  @Test
  @DisplayName("A plain file in another archive is not next to the profile file either")
  public void aPlainFileInAnotherArchiveIsNotNextToItEither() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan-approval.yaml"), Set.of("loan-approval-prod.yaml")),
                List.of("loan-approval"))
            .orElseThrow()
            .contains("loan-approval-prod.yaml"));

  }

  @Test
  @DisplayName("The plain file of one module is not read as a profile file of another")
  public void thePlainFileOfOneModuleIsNoProfileFileOfAnother() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan.yaml", "loan-approval.yaml")),
                List.of("loan", "loan-approval"))
            .isEmpty());

  }

  @Test
  @DisplayName("A profile file of the module with the longer ID needs that module's plain file")
  public void theLongerModuleIdOwnsTheProfileFile() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan.yaml", "loan-approval-prod.yaml")),
                List.of("loan", "loan-approval"))
            .orElseThrow()
            .contains("'loan-approval.yaml'"));

  }

  @Test
  @DisplayName("An application whose modules ship no profile file is not warned")
  public void anApplicationWithoutProfileFilesIsNotWarned() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of("loan-approval.yaml", "loan-approval.properties")),
                List.of("loan-approval"))
            .isEmpty());

  }

  @Test
  @DisplayName("An application without workflow module files is not warned")
  public void anApplicationWithoutWorkflowModuleFilesIsNotWarned() {

    Assertions.assertTrue(
        WorkflowModuleBuildStepProcessor
            .messageAboutProfileFilesWithoutTheirPlainFile(
                List.of(Set.of()),
                List.of("loan-approval"))
            .isEmpty());

  }

}
