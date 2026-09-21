package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * The assertion is run against small repositories this test writes into a temporary
 * directory, one per case.
 * <p>
 * The POMs are written and not checked in, because a file named {@code pom.xml} in the
 * source tree is read by every check which walks this repository. An example of the
 * mistake lying there would be reported as the mistake.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  private static final Set<String> THE_TOOLS = Set
      .of("org.projectlombok:lombok", "org.mapstruct:mapstruct-processor");

  @Test
  @DisplayName("A repository which keeps its tools to itself passes")
  public void aRepositoryWhichKeepsItsToolsToItselfPasses(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>1.18.42</version>
                <scope>provided</scope>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <scope>provided</scope>
            </dependency>
            <dependency>
              <groupId>org.mapstruct</groupId>
              <artifactId>mapstruct-processor</artifactId>
              <scope>test</scope>
            </dependency>
            <dependency>
              <groupId>org.mapstruct</groupId>
              <artifactId>mapstruct</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);

    assertDoesNotThrow(
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

  }

  @Test
  @DisplayName("A tool declared at compile scope is reported with the file which declares it")
  public void aToolDeclaredAtCompileScopeIsReported(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
        </project>
        """);
    writePom(root.resolve("runtime"), PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>runtime</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <scope>compile</scope>
            </dependency>
          </dependencies>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

    assertTrue(
        failure
            .getMessage()
            .contains("runtime/pom.xml declares org.projectlombok:lombok with scope compile"),
        () -> "The failure does not say which file declares the tool: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A declaration without a scope is reported rather than ending in a NullPointerException")
  public void aDeclarationWithoutAScopeIsReported(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

    assertTrue(
        failure
            .getMessage()
            .contains("with scope compile, because it names none"),
        () -> "The failure does not say that a missing scope means compile: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A tool a dependencyManagement marks optional is reported as a promise which is not kept")
  public void aToolManagedAsOptionalIsReported(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>1.18.42</version>
                <optional>true</optional>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <scope>provided</scope>
            </dependency>
          </dependencies>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

    assertTrue(
        failure
            .getMessage()
            .contains("pom.xml manages org.projectlombok:lombok as optional"),
        () -> "The failure does not name the management entry: "
            + failure.getMessage());
    assertFalse(
        failure
            .getMessage()
            .contains("is declared so that applications get it"),
        () -> "The failure reports a declaration although every declaration is fine: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("Both mistakes at once are reported as two separate messages")
  public void bothMistakesAtOnceAreReportedSeparately(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>1.18.42</version>
                <optional>true</optional>
              </dependency>
            </dependencies>
          </dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

    assertTrue(
        failure
            .getMessage()
            .contains("is declared so that applications get it"),
        () -> "The failure does not report the declaration: "
            + failure.getMessage());
    assertTrue(
        failure
            .getMessage()
            .contains("which is a promise Maven never keeps"),
        () -> "The failure does not report the management entry: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A tool only a plugin uses is no finding")
  public void aToolOnlyAPluginUsesIsNoFinding(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <dependencies>
                  <dependency>
                    <groupId>org.projectlombok</groupId>
                    <artifactId>lombok</artifactId>
                    <version>1.18.42</version>
                  </dependency>
                </dependencies>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    assertDoesNotThrow(
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

  }

  @Test
  @DisplayName("A declaration which says optional itself is no finding, because Maven keeps that flag")
  public void aDeclarationWhichSaysOptionalItselfIsNoFinding(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <optional>true</optional>
            </dependency>
          </dependencies>
        </project>
        """);

    assertDoesNotThrow(
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

  }

  @Test
  @DisplayName("The caller's choice of file decides what is read")
  public void theCallersChoiceOfFileDecidesWhatIsRead(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    writePom(root, PublishedPoms.THE_FLATTENED_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <version>1.18.42</version>
              <scope>provided</scope>
            </dependency>
          </dependencies>
        </project>
        """);

    assertDoesNotThrow(
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_FLATTENED_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS),
        "The flattened file resolves the scope, so reading it must accept this module");
    assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS),
        "The source file names no scope, so reading it must report this module");

  }

  @Test
  @DisplayName("A module without a flattened file is read from its source POM")
  public void aModuleWithoutAFlattenedFileIsReadFromItsSource(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <packaging>pom</packaging>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    writePom(root.resolve("runtime"), PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>runtime</artifactId>
        </project>
        """);
    writePom(root.resolve("runtime"), PublishedPoms.THE_FLATTENED_POM, """
        <project>
          <artifactId>runtime</artifactId>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_FLATTENED_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

    assertTrue(
        failure
            .getMessage()
            .contains("pom.xml declares org.projectlombok:lombok"),
        () -> "The module which flattens nothing was not read at all: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A flattened file the build never wrote ends the test instead of passing it")
  public void aFlattenedFileTheBuildNeverWroteEndsTheTest(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
        </project>
        """);

    final var failure = assertThrows(AssertionError.class,
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_FLATTENED_POM));

    assertTrue(
        failure
            .getMessage()
            .contains("process-resources"),
        () -> "The failure does not say which phase writes the file: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("What a build wrote below target is left out")
  public void whatABuildWroteBelowTargetIsLeftOut(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
        </project>
        """);
    writePom(root.resolve("target").resolve("test-classes").resolve("example"),
        PublishedPoms.THE_SOURCE_POM, """
            <project>
              <artifactId>example</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.projectlombok</groupId>
                  <artifactId>lombok</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);

    assertDoesNotThrow(
        () -> PublishedPoms
            .ofTheRepositoryAt(root, PublishedPoms.THE_SOURCE_POM)
            .handAnApplicationNoToolOfTheBuild(THE_TOOLS));

  }

  @Test
  @DisplayName("The repository root is found above the module which runs the test")
  public void theRepositoryRootIsFoundAboveTheModuleWhichRunsTheTest(
      @TempDir final Path root) throws IOException {

    writePom(root, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>root</artifactId>
          <dependencies>
            <dependency>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </dependency>
          </dependencies>
        </project>
        """);
    final var module = root.resolve("runtime");
    writePom(module, PublishedPoms.THE_SOURCE_POM, """
        <project>
          <artifactId>runtime</artifactId>
        </project>
        """);

    final var failure = whileTheWorkingDirectoryIs(module,
        () -> assertThrows(AssertionError.class,
            () -> PublishedPoms
                .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
                .handAnApplicationNoToolOfTheBuild(THE_TOOLS)));

    assertTrue(
        failure
            .getMessage()
            .contains("pom.xml declares org.projectlombok:lombok"),
        () -> "The POM above the module running the test was not read: "
            + failure.getMessage());

  }

  @Test
  @DisplayName("A working directory which is no module says so")
  public void aWorkingDirectoryWhichIsNoModuleSaysSo(
      @TempDir final Path somewhere) {

    final var failure = whileTheWorkingDirectoryIs(somewhere,
        () -> assertThrows(AssertionError.class,
            () -> PublishedPoms.ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)));

    assertTrue(
        failure
            .getMessage()
            .contains("holds no pom.xml"),
        () -> "The failure does not say what is missing: "
            + failure.getMessage());

  }

  /**
   * Runs something with the working directory the assertion reads set to the given
   * directory. The property is put back afterwards, because every later test in this fork
   * reads it too.
   */
  private static AssertionError whileTheWorkingDirectoryIs(
      final Path directory,
      final Supplier<AssertionError> what) {

    final var before = System.getProperty("user.dir");
    System.setProperty("user.dir", directory.toString());
    try {
      return what.get();
    } finally {
      System.setProperty("user.dir", before);
    }

  }

  /** Writes one POM, creating the module's directory where it is not there yet. */
  private static void writePom(
      final Path module,
      final String fileName,
      final String content) throws IOException {

    Files.createDirectories(module);
    Files.writeString(module.resolve(fileName), content);

  }

}
