package io.vanillabp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The rules which keep a released changeset unchangeable. Liquibase compares checksums of what it
 * applied, so editing a changeset afterwards breaks the next installation of somebody else's
 * database - and getting back from there costs a support case. This is an open-source project, so
 * the rules are enforced here instead of being written down and hoped for:
 * <ul>
 * <li>the master changelog holds no changeset of its own, only properties and includes,</li>
 * <li>every included file exists and declares the shared logical path, which is what lets the
 * release rename a file without changing what Liquibase records,</li>
 * <li>a file of a released version carries a checksum next to it and still matches it,</li>
 * <li>every file except <code>latest.xml</code> is released, so a rename by hand without pinning is
 * caught as well.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class ChangelogRulesTest {

  private static final Path SCHEMA = Path.of("src/main/resources/vanillabp/schema");

  private static final String LOGICAL_FILE_PATH = "logicalFilePath=\"vanillabp/schema\"";

  private static final Pattern INCLUDE = Pattern
      .compile("<include\\s+file=\"vanillabp/schema/([^\"]+)\"");

  private static String read(
      final Path path) throws Exception {

    return Files.readString(path);

  }

  private static List<Path> changelogFiles() throws Exception {

    try (var files = Files.list(SCHEMA)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".xml"))
          .filter(path -> !path.getFileName().toString().equals("changelog.xml"))
          .sorted()
          .toList();
    }

  }

  private static String sha256(
      final Path path) throws Exception {

    final var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    final var hex = new StringBuilder();
    for (final var b : digest) {
      hex.append("%02x".formatted(b));
    }
    return hex.toString();

  }

  @Test
  @DisplayName("The master changelog only includes - a changeset in it could never be pinned")
  public void theMasterHoldsNoChangeset() throws Exception {

    final var master = read(SCHEMA.resolve("changelog.xml"));

    assertFalse(master.contains("<changeSet"), "changelog.xml must only define properties and includes");
    // right after a release pinned it, latest.xml does not exist yet - the next one is opened by
    // 'schema-version.sh open'. Wherever it exists, it has to be included, or its changesets would
    // silently do nothing
    if (Files.exists(SCHEMA.resolve("latest.xml"))) {
      assertTrue(master.contains("<include file=\"vanillabp/schema/latest.xml\"/>"), master);
    }

  }

  @Test
  @DisplayName("Every included file exists, and every version file shares the logical path")
  public void everyIncludeResolvesAndSharesTheLogicalPath() throws Exception {

    final var master = read(SCHEMA.resolve("changelog.xml"));
    final var included = INCLUDE.matcher(master).results().map(result -> result.group(1)).toList();

    assertFalse(included.isEmpty(), "no include found in changelog.xml");
    for (final var name : included) {
      assertTrue(Files.exists(SCHEMA.resolve(name)), "included but missing: "
          + name);
    }
    assertEquals(
        included.stream().sorted().toList(),
        changelogFiles().stream().map(path -> path.getFileName().toString()).sorted().toList(),
        "every file next to the master has to be included, and nothing else");

    for (final var file : changelogFiles()) {
      assertTrue(
          read(file).contains(LOGICAL_FILE_PATH),
          "%s has to declare %s, otherwise renaming it on release changes what Liquibase recorded"
              .formatted(file.getFileName(), LOGICAL_FILE_PATH));
    }

  }

  @Test
  @DisplayName("A released changelog still matches its checksum, and only latest.xml has none")
  public void releasedChangelogsArePinned() throws Exception {

    for (final var file : changelogFiles()) {
      final var name = file.getFileName().toString();
      final var checksumFile = SCHEMA.resolve(name
          + ".sha256");

      if (name.equals("latest.xml")) {
        assertFalse(
            Files.exists(checksumFile),
            "latest.xml is the version under development and must not be pinned yet");
        continue;
      }

      assertTrue(
          Files.exists(checksumFile),
          """
              %s has no checksum next to it. A file of a released version is pinned by \
              'schema/bin/schema-version.sh pin <version>', which the release workflow calls - a \
              version file created by hand skips that and must not stay."""
              .formatted(name));
      assertEquals(
          read(checksumFile).strip(),
          sha256(file),
          """
              %s does not match its checksum any more! A changeset which was applied to a database \
              must never change: Liquibase compares checksums and refuses to run, and every \
              installation which already applied this file needs manual repair. Revert the file and \
              put your change into latest.xml instead."""
              .formatted(name));
    }

  }

}
