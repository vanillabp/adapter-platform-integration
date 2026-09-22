package io.vanillabp.integration.test.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

/**
 * What the POMs of a repository hand an application, read back out of the files the
 * build publishes.
 * <p>
 * A build uses tools which only translate the source: Lombok, MapStruct's processor,
 * the annotation processors of Spring Boot and Quarkus. Each one is read while javac
 * runs and has nothing left to do once the class file exists. An application asked for
 * a workflow engine, not for them, and every jar it did not ask for is one more thing to
 * scan and to ship. The scope which says that is {@code provided}: it puts the jar on
 * our own compile path and hands it to nobody.
 * <p>
 * Who calls this: every VanillaBP repository which publishes artifacts, from a test
 * class of its own with a name of its own. They all can make this mistake, and they make
 * it in the same way, so the check lives here instead of in a test class each repository
 * copies. A copy drifts, and the value of this check is that it still runs in two years.
 * <p>
 * The caller brings two things. The first is the name of the file its modules publish,
 * which differs per repository and is explained below. The second is the list of its own
 * tools, each as {@code groupId:artifactId}. That list is not built in, because only the
 * repository knows what a tool of its build is. {@code org.mapstruct:mapstruct} for
 * example runs with the application, since {@code Mappers.getMapper(...)} is called
 * there; the tool is {@code org.mapstruct:mapstruct-processor} alone. A built-in list
 * would be wrong at the first dependency somebody else brings.
 * <p>
 * Which file counts is the caller's answer too. A repository which publishes its sources
 * as they are names {@link #THE_SOURCE_POM}. A repository which publishes through the
 * flatten plugin names {@link #THE_FLATTENED_POM}, and then the check reads what an
 * application really resolves. That is worth more than it sounds. In the flattened file
 * a scope which stands only in a {@code dependencyManagement} is already resolved into
 * the declaration, test dependencies are gone and the profiles are applied. So the
 * flattened file shows both forms of the mistake at once, while the source file shows
 * neither of them. Where a module writes no flattened file its source POM is read, since
 * that is what such a module publishes: the flatten plugin leaves a module of packaging
 * {@code pom} alone.
 * <p>
 * A failure reports two things separately. A declaration which reaches an application is
 * a defect in that module. An {@code optional} entry in a {@code dependencyManagement}
 * is a promise which is never kept, because Maven copies a managed version, scope and
 * exclusions into a declaration but not that flag. The two need different repairs, and a
 * reader who gets only one sentence repairs the wrong place. That is how Lombok stayed
 * at compile scope in the platform for as long as it did. The flag sat in the root POM,
 * the modules declared Lombok bare, and every application whose own BOM names Lombok,
 * which the Spring Boot BOM and the Quarkus BOM both do, resolved it and shipped it.
 * <p>
 * The dependencies are read along {@code project/dependencies/dependency} because a POM
 * repeats the name {@code dependency}. A plugin has dependencies of its own, and those
 * run inside Maven and reach no application. Asking the document for every element of
 * that name counts them as if the module had declared them, which is a finding nobody
 * can act on.
 */
public final class PublishedPoms {

  /** The file a repository publishes when it publishes its sources as they are. */
  public static final String THE_SOURCE_POM = "pom.xml";

  /** The file the flatten plugin writes into a module's directory. */
  public static final String THE_FLATTENED_POM = ".flattened-pom.xml";

  /**
   * The scopes which keep a dependency away from an application. A declaration without a
   * scope is none of them: it means {@code compile}.
   */
  private static final Set<String> SCOPES_WHICH_REACH_NO_APPLICATION = Set
      .of("provided", "test");

  private final Path repositoryRoot;

  private final List<Path> publishedFiles;

  private PublishedPoms(
      final Path repositoryRoot,
      final List<Path> publishedFiles) {

    this.repositoryRoot = repositoryRoot;
    this.publishedFiles = publishedFiles;

  }

  /**
   * The published POMs of the repository the running test belongs to.
   * <p>
   * The repository root is searched for rather than passed in, so the test also runs
   * from an IDE and from a git worktree: it is the highest directory above the module
   * running the test which still holds a {@code pom.xml}.
   *
   * @param fileEachModulePublishes {@link #THE_SOURCE_POM} or {@link #THE_FLATTENED_POM},
   *          whichever this repository publishes
   * @return The published POMs, ready to be asked what they hand an application
   * @throws AssertionError If the test does not run in a module of a repository, or if
   *           the named file is nowhere. A test which passes because it read nothing is
   *           worse than no test at all.
   */
  public static PublishedPoms ofTheRepositoryUnderTest(
      final String fileEachModulePublishes) {

    return ofTheRepositoryAt(repositoryRootOfTheRunningTest(), fileEachModulePublishes);

  }

  /**
   * The published POMs below a directory which is named rather than searched for. The
   * tests of this class work this way, and so does a repository whose root cannot be
   * found by walking up from the module which runs the test.
   *
   * @param repositoryRoot The directory the modules lie in
   * @param fileEachModulePublishes {@link #THE_SOURCE_POM} or {@link #THE_FLATTENED_POM},
   *          whichever this repository publishes
   * @return The published POMs, ready to be asked what they hand an application
   * @throws AssertionError If the named file is nowhere below the root
   */
  public static PublishedPoms ofTheRepositoryAt(
      final Path repositoryRoot,
      final String fileEachModulePublishes) {

    final var root = repositoryRoot
        .toAbsolutePath()
        .normalize();
    final var published = new ArrayList<Path>();
    var modulesWhichWroteTheFile = 0;
    for (final var module : modulesOf(root)) {
      final var file = module.resolveSibling(fileEachModulePublishes);
      if (Files.isRegularFile(file)) {
        published.add(file);
        modulesWhichWroteTheFile++;
      } else {
        // a module of packaging 'pom' publishes its source POM, because the flatten
        // plugin skips such a module
        published.add(module);
      }
    }
    if (!THE_SOURCE_POM.equals(fileEachModulePublishes) && (modulesWhichWroteTheFile == 0)) {
      throw new AssertionError(
          ("No module below '%s' holds a '%s', so this check would read the source POMs and say "
              + "nothing about what is published. The flatten plugin writes that file in the phase "
              + "'process-resources', which a run from an IDE and a build narrowed down to later "
              + "phases both skip.")
              .formatted(root, fileEachModulePublishes));
    }
    return new PublishedPoms(root, published);

  }

  /**
   * Asserts that no published POM of the repository hands an application a tool which
   * only translates the source, and that no {@code dependencyManagement} promises a tool
   * an {@code optional} which Maven ignores.
   * <p>
   * A declaration passes when its scope keeps it away from an application, which is
   * {@code provided} or {@code test}, or when the declaration itself says
   * {@code optional}. The flag counts here and not in the {@code dependencyManagement},
   * which is the whole difference between the two messages this may throw.
   * <p>
   * The list holds for the whole repository, while a finding falls per module. A tool
   * which is not in the list is therefore invisible here, in every module at once, and
   * a tool which is in it is checked in every module the same way. A module which is
   * itself a library of test tools needs the second half of that to bend: it uses the
   * tool in {@code src/main/java} and hands it on at compile scope on purpose. That is
   * what {@code business-cockpit} did on 2026-09-22, and this check said nothing only
   * because {@code test-utils} was no tool of that build's list.
   * <p>
   * A repository which keeps a tool right everywhere and passes it on in exactly one
   * module cannot say so today. It would have to drop the tool from the list and lose
   * the check for it everywhere else. The way out is an exception which names module and
   * tool together, and the module is named by its {@code groupId:artifactId} rather than
   * by the path of its POM. The coordinates stand in the file this check already reads
   * and they survive a move in the directory tree. In a flattened file the
   * {@code groupId} is sometimes only inherited from the parent, and whoever reads the
   * coordinates has to take that case along.
   *
   * @param toolsOfTheBuild The tools of this repository, each as
   *          {@code groupId:artifactId}, for example
   *          {@code org.projectlombok:lombok}
   * @throws AssertionError If any published POM declares one of them so that an
   *           application gets it, or if a {@code dependencyManagement} marks one of them
   *           optional
   */
  public void handAnApplicationNoToolOfTheBuild(
      final Set<String> toolsOfTheBuild) {

    final var reachingAnApplication = new ArrayList<String>();
    final var promisedInVain = new ArrayList<String>();

    for (final var pom : publishedFiles) {
      final var project = projectOf(pom);
      for (final var declaration : dependenciesOf(project)) {
        final var tool = coordinateOf(declaration);
        if (!toolsOfTheBuild.contains(tool)) {
          continue;
        }
        final var scope = textOfChild(declaration, "scope");
        // the scope may be missing, and asking an immutable set whether it contains null
        // throws instead of answering
        if ((scope != null) && SCOPES_WHICH_REACH_NO_APPLICATION.contains(scope)) {
          continue;
        }
        if ("true".equals(textOfChild(declaration, "optional"))) {
          continue;
        }
        reachingAnApplication
            .add(
                "  %s declares %s with scope %s"
                    .formatted(repositoryRoot.relativize(pom), tool, scope == null
                        ? "compile, because it names none"
                        : scope));
      }
      for (final var managed : dependenciesOf(childOf(project, "dependencyManagement"))) {
        final var tool = coordinateOf(managed);
        if (toolsOfTheBuild.contains(tool) && "true"
            .equals(textOfChild(managed, "optional"))) {
          promisedInVain
              .add("  %s manages %s as optional".formatted(repositoryRoot.relativize(pom), tool));
        }
      }
    }

    if (reachingAnApplication.isEmpty() && promisedInVain.isEmpty()) {
      return;
    }

    final var message = new StringBuilder();
    if (!reachingAnApplication.isEmpty()) {
      message
          .append(
              ("A tool which only translates the source is declared so that applications get it:\n"
                  + "%s\n"
                  + "Such a tool belongs in scope 'provided'. Write the scope at the declaration "
                  + "itself, because an application resolves the declaration and nothing else.")
                  .formatted(String.join("\n", reachingAnApplication)));
    }
    if (!promisedInVain.isEmpty()) {
      if (!message.isEmpty()) {
        message.append("\n\n");
      }
      message
          .append(
              ("A dependencyManagement marks a tool optional, which is a promise Maven never "
                  + "keeps:\n"
                  + "%s\n"
                  + "Maven copies a managed version, scope and exclusions into a declaration, but "
                  + "not the optional flag. So the flag in this entry changes nothing. Write the "
                  + "scope 'provided' at the declaration itself, which is the place an application "
                  + "reads.")
                  .formatted(String.join("\n", promisedInVain)));
    }
    throw new AssertionError(message.toString());

  }

  /**
   * The highest directory above the module running the test which still holds a
   * {@code pom.xml}.
   */
  private static Path repositoryRootOfTheRunningTest() {

    var root = Path
        .of(System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize();
    if (!Files.isRegularFile(root.resolve(THE_SOURCE_POM))) {
      throw new AssertionError(
          ("The working directory '%s' holds no pom.xml, so this check cannot tell which POMs "
              + "belong to the repository. Surefire runs a test in its module's directory.")
              .formatted(root));
    }
    for (var above = root.getParent(); (above != null) && Files
        .isRegularFile(above.resolve(THE_SOURCE_POM)); above = above.getParent()) {
      root = above;
    }
    return root;

  }

  /** The source POM of every module below the root, with the build output left out. */
  private static List<Path> modulesOf(
      final Path root) {

    try (var files = Files.walk(root)) {
      return files
          .filter(path -> path.endsWith(THE_SOURCE_POM))
          .filter(path -> !runsThroughBuildOutput(root, path))
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new AssertionError("Cannot read the modules below '%s'".formatted(root), e);
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

  /** The {@code project} element of a POM. */
  private static Element projectOf(
      final Path pom) {

    try {
      final var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      return factory
          .newDocumentBuilder()
          .parse(pom.toFile())
          .getDocumentElement();
    } catch (final Exception e) {
      throw new AssertionError("Cannot read the POM '%s'".formatted(pom), e);
    }

  }

  /**
   * The {@code dependency} elements below the {@code dependencies} of the given element,
   * and nothing else. Passing the {@code project} gives what the module declares, and a
   * plugin's own dependencies stay out because they hang below the plugin.
   */
  private static List<Element> dependenciesOf(
      final Element holder) {

    return childrenOf(childOf(holder, "dependencies"), "dependency");

  }

  /** A dependency named the way a failure message can act on. */
  private static String coordinateOf(
      final Element dependency) {

    return "%s:%s".formatted(
        textOfChild(dependency, "groupId"),
        textOfChild(dependency, "artifactId"));

  }

  /**
   * The direct child elements of the given name, or nothing where the parent is absent.
   * Direct, because a POM repeats names.
   */
  private static List<Element> childrenOf(
      final Element parent,
      final String tagName) {

    final var children = new ArrayList<Element>();
    if (parent == null) {
      return children;
    }
    final var nodes = parent.getChildNodes();
    for (var index = 0; index < nodes.getLength(); index++) {
      if ((nodes.item(index) instanceof final Element element) && tagName
          .equals(element.getTagName())) {
        children.add(element);
      }
    }
    return children;

  }

  /** The first direct child element of that name, or {@code null} where there is none. */
  private static Element childOf(
      final Element parent,
      final String tagName) {

    final var children = childrenOf(parent, tagName);
    return children.isEmpty()
        ? null
        : children.get(0);

  }

  /** The text of a direct child element, or {@code null} where there is none. */
  private static String textOfChild(
      final Element parent,
      final String tagName) {

    final var child = childOf(parent, tagName);
    return child == null
        ? null
        : child
            .getTextContent()
            .trim();

  }

}
