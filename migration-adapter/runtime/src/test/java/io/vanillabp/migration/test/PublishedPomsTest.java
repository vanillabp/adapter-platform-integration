package io.vanillabp.migration.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A tool which translates our source stays out of the runtime classpath of the
 * applications using our artifacts.
 * <p>
 * Lombok, MapStruct's processor and the two platform processors do their work while
 * javac runs and have nothing left to do once the class file exists. An application asked
 * for a workflow engine, not for them, and every jar it did not ask for is one more thing
 * to scan, to ship and to answer a CVE report about. The scope which says that is
 * {@code provided}: it puts the jar on our own compile path and hands it to nobody.
 * <p>
 * The rule is checked on the POMs themselves because those files are what we publish.
 * This repository writes no flattened POM, so an application reads the same
 * {@code pom.xml} a reviewer reads. What it does not read is our
 * {@code dependencyManagement} alone: Maven copies a managed version, scope and
 * exclusions into a dependency, but not the {@code optional} flag. That is how Lombok
 * left the platform at compile scope for as long as it did. The flag sat in the root POM,
 * the four modules declared Lombok bare, and every application whose own BOM names
 * Lombok, which the Spring Boot BOM and the Quarkus BOM both do, resolved it and shipped
 * it. So the scope has to stand at the declaration, and this test reads the declarations.
 * <p>
 * Entries below a {@code dependencyManagement} are left out on purpose. They declare
 * nothing; they say which version a declaration elsewhere gets. The BOM this repository
 * publishes is nothing but such entries, and a scope in it would be an order to the
 * application rather than a statement about us.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools which translate the source. Each one is read by javac and by nothing which
   * runs afterwards.
   */
  private static final Set<String> TRANSLATION_TIME_TOOLS = Set
      .of(
          "org.projectlombok:lombok",
          "org.mapstruct:mapstruct-processor",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "io.quarkus:quarkus-extension-processor",
          "io.vanillabp:vanillabp-mapstruct-fluent-accessors");

  /** The scopes which keep a dependency away from an application. */
  private static final Set<String> SCOPES_WHICH_REACH_NO_APPLICATION = Set
      .of("provided", "test");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() throws Exception {

    final var root = repositoryRoot();
    final var offenders = new ArrayList<String>();

    for (final var pom : poms(root)) {
      for (final var declaration : declaredDependencies(pom)) {
        final var tool = textOf(declaration, "groupId")
            + ":"
            + textOf(declaration, "artifactId");
        if (!TRANSLATION_TIME_TOOLS.contains(tool)) {
          continue;
        }
        final var scope = textOf(declaration, "scope");
        if (SCOPES_WHICH_REACH_NO_APPLICATION.contains(scope)) {
          continue;
        }
        offenders
            .add(
                "  %s declares %s with scope %s"
                    .formatted(
                        root.relativize(pom),
                        tool,
                        scope == null
                            ? "compile, because it names none"
                            : scope));
      }
    }

    if (offenders.isEmpty()) {
      return;
    }
    throw new AssertionError(
        ("A tool which only translates our source is declared so that applications get it:\n%s\n"
            + "Such a tool belongs in scope 'provided'. Write the scope at the declaration, not "
            + "only in a dependencyManagement: the optional flag of a managed dependency never "
            + "reaches the published POM, and an application whose own BOM names the tool "
            + "overrides whatever we managed for it.")
            .formatted(String.join("\n", offenders)));

  }

  /**
   * The root of this repository: the highest directory above the module running this
   * test which still holds a POM. It is found rather than passed in, so the test also
   * runs from an IDE and from a git worktree.
   */
  private Path repositoryRoot() {

    var root = Path
        .of(System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize();
    if (!Files.isRegularFile(root.resolve("pom.xml"))) {
      throw new AssertionError(
          "The working directory '%s' holds no pom.xml, so this test cannot tell which POMs "
              .formatted(root)
              + "belong to the repository. Surefire runs a test in its module's directory.");
    }
    for (var above = root.getParent(); (above != null) && Files
        .isRegularFile(above.resolve("pom.xml")); above = above.getParent()) {
      root = above;
    }
    return root;

  }

  /** Every POM of the repository, with the build output left out. */
  private List<Path> poms(
      final Path root) throws IOException {

    try (var files = Files.walk(root)) {
      return files
          .filter(path -> path.endsWith("pom.xml"))
          .filter(path -> !runsThroughBuildOutput(root, path))
          .sorted()
          .toList();
    }

  }

  /** Whether a path runs through a build output directory. */
  private static boolean runsThroughBuildOutput(
      final Path root,
      final Path pom) {

    final var relative = root.relativize(pom);
    for (var index = 0; index < relative.getNameCount(); index++) {
      if ("target".equals(relative.getName(index).toString())) {
        return true;
      }
    }
    return false;

  }

  /**
   * The dependencies a POM declares, which is every {@code <dependency>} outside a
   * {@code <dependencyManagement>}.
   */
  private List<Element> declaredDependencies(
      final Path pom) throws Exception {

    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    final Element project;
    try {
      project = factory
          .newDocumentBuilder()
          .parse(pom.toFile())
          .getDocumentElement();
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read the POM '%s'".formatted(pom), e);
    }

    final var dependencies = project.getElementsByTagName("dependency");
    final var declared = new ArrayList<Element>();
    for (var index = 0; index < dependencies.getLength(); index++) {
      final var dependency = (Element) dependencies.item(index);
      if (!isManagement(dependency)) {
        declared.add(dependency);
      }
    }
    return declared;

  }

  /** Whether a {@code <dependency>} sits below a {@code <dependencyManagement>}. */
  private static boolean isManagement(
      final Element dependency) {

    for (Node node = dependency.getParentNode(); node != null; node = node.getParentNode()) {
      if ("dependencyManagement".equals(node.getNodeName())) {
        return true;
      }
    }
    return false;

  }

  /** The text of a child element, or {@code null} where the POM has none. */
  private static String textOf(
      final Element dependency,
      final String tagName) {

    final var children = dependency.getChildNodes();
    for (var index = 0; index < children.getLength(); index++) {
      final var child = children.item(index);
      if (tagName.equals(child.getNodeName())) {
        return child
            .getTextContent()
            .trim();
      }
    }
    return null;

  }

}
