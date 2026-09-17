package io.vanillabp.integration.adapter.migration.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The places a workflow module may put its configuration file, shared by both platform
 * integrations so that a module behaves the same wherever it runs.
 * <p>
 * Four places, and they are styles rather than a ranking: next to the rest of the
 * application, in a <code>config</code> directory, next to the BPMN files of the module,
 * or in a <code>config</code> directory of the module. VanillaBP prescribes none of them
 * (see decision 65 in the repository's DECISIONS.md).
 * <p>
 * There is no order among the four, because a file belongs in exactly one of them. Two
 * copies of the same file end the boot rather than let one of them win, which nobody could
 * have guessed from the outside.
 */
public final class WorkflowModuleConfigFiles {

  private WorkflowModuleConfigFiles() {
  }

  /**
   * @param workflowModuleId The ID of the workflow module
   * @return The directories a file of that module is read in, each ending in a slash
   *         except the classpath root, which is the empty string
   */
  public static List<String> directoriesOf(
      final String workflowModuleId) {

    return List.of(
        "",
        "config/",
        "%s/".formatted(workflowModuleId),
        "%s/config/".formatted(workflowModuleId));

  }

  /**
   * @param workflowModuleId The ID of the workflow module
   * @param filename The name of the file, for example <code>loan-approval-prod.yaml</code>
   * @return The classpath locations that file is read at
   */
  public static List<String> locationsOf(
      final String workflowModuleId,
      final String filename) {

    return directoriesOf(workflowModuleId)
        .stream()
        .map(directory -> "%s%s".formatted(directory, filename))
        .toList();

  }

  /**
   * Builds the message for a workflow module which ships the same file in more than one of
   * the four places. Both platforms refuse to start on it: with no ranking among the places
   * there is no answer to which of the two files applies, and inventing one would mean a
   * setting whose source cannot be seen from the outside.
   *
   * @param workflowModuleId The ID of the workflow module
   * @param placesPerFilename Where each file of that module was found, keyed by its name
   * @return The message, or nothing where every file lies in one place only
   */
  public static Optional<String> messageAboutAFileFoundInMoreThanOnePlace(
      final String workflowModuleId,
      final Map<String, ? extends Collection<String>> placesPerFilename) {

    final var filesFoundTwice = placesPerFilename
        .entrySet()
        .stream()
        .filter(file -> file.getValue().size() > 1)
        .sorted(Map.Entry.comparingByKey())
        .toList();
    if (filesFoundTwice.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of("""
        The workflow module '%s' ships the same configuration file in more than one place:
          %s
        A workflow module may put a file at the classpath root, in a 'config' directory, in \
        a directory named after the module, or in a 'config' directory of that directory. \
        Those are four styles and none of them ranks above another, so a file which lies in \
        two of them has no answer to which one applies. Delete all but one of the files \
        above, or merge them into one.""".formatted(
        workflowModuleId,
        filesFoundTwice
            .stream()
            .map(file -> "%s, found at %s".formatted(
                file.getKey(),
                file
                    .getValue()
                    .stream()
                    .sorted()
                    .map("'%s'"::formatted)
                    .collect(Collectors.joining(" and "))))
            .collect(Collectors.joining("\n  "))));

  }

}
