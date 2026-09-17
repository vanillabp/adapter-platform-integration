package io.vanillabp.migration.test.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.WorkflowModuleConfigFiles;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The four places a workflow module may put its configuration file, and what is said about
 * a module which uses two of them for the same file. Both platform integrations read this
 * list, so a change here changes both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleConfigFilesTest {

  @Test
  @DisplayName("The four places are the classpath root, a config directory, and both inside the module directory")
  public void theFourPlacesAreNamed() {

    Assertions.assertEquals(
        List.of(
            "loan-approval.yaml",
            "config/loan-approval.yaml",
            "loan-approval/loan-approval.yaml",
            "loan-approval/config/loan-approval.yaml"),
        WorkflowModuleConfigFiles.locationsOf("loan-approval", "loan-approval.yaml"));

  }

  @Test
  @DisplayName("A profile file is read at the same four places")
  public void aProfileFileIsReadAtTheSamePlaces() {

    Assertions.assertEquals(
        List.of(
            "loan-approval-prod.properties",
            "config/loan-approval-prod.properties",
            "loan-approval/loan-approval-prod.properties",
            "loan-approval/config/loan-approval-prod.properties"),
        WorkflowModuleConfigFiles.locationsOf("loan-approval", "loan-approval-prod.properties"));

  }

  @Test
  @DisplayName("A file found in two places is named with both of them and with the way out")
  public void aFileFoundTwiceIsNamed() {

    final var message = WorkflowModuleConfigFiles
        .messageAboutAFileFoundInMoreThanOnePlace(
            "loan-approval",
            Map.of(
                "loan-approval.yaml",
                Set.of("config/loan-approval.yaml", "loan-approval/loan-approval.yaml"),
                "loan-approval.properties",
                Set.of("loan-approval.properties")))
        .orElseThrow();

    Assertions.assertTrue(
        message.contains("'config/loan-approval.yaml' and 'loan-approval/loan-approval.yaml'"),
        "both places are not named: %s".formatted(message));
    Assertions.assertFalse(
        message.contains("loan-approval.properties,"),
        "a file lying in one place only is named as well: %s".formatted(message));
    Assertions.assertTrue(
        message.contains("Delete all but one"),
        "the way out is not offered: %s".formatted(message));

  }

  @Test
  @DisplayName("Nothing is said where every file lies in one place")
  public void aModuleWithoutSuchAFileIsNotNamed() {

    Assertions.assertTrue(
        WorkflowModuleConfigFiles
            .messageAboutAFileFoundInMoreThanOnePlace(
                "loan-approval",
                Map.of("loan-approval.yaml", Set.of("config/loan-approval.yaml")))
            .isEmpty(),
        "a message was built although no file lies in two places");

  }

}
