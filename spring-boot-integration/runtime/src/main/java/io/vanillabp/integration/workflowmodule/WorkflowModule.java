package io.vanillabp.integration.workflowmodule;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.Builder;
import lombok.Getter;

/**
 * Meta-data of a workflow module.
 */
@Getter
@Builder
public class WorkflowModule {

  /**
   * The location of workflow module definition files.
   */
  public static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  /**
   * A class directory of a Spring Boot executable JAR as the current loader names it, for
   * example <code>jar:nested:/app.jar/!BOOT-INF/classes/!/</code>. The group is the path of
   * the JAR.
   */
  private static final Pattern CLASSES_OF_A_NESTED_JAR = Pattern.compile("^jar:nested:(.+)/!BOOT-INF/classes/!/$");

  /**
   * The same directory as the loader of Spring Boot before 3.2 names it, for example
   * <code>jar:file:/app.jar!/BOOT-INF/classes!/</code>. The group is the path of the JAR.
   */
  private static final Pattern CLASSES_OF_A_CLASSIC_JAR = Pattern.compile("^jar:file:([^!]+)!/BOOT-INF/classes!/$");

  /**
   * The root of a plain JAR, for example <code>jar:file:/app.jar!/</code>. The group is the
   * path of the JAR.
   */
  private static final Pattern ROOT_OF_A_JAR = Pattern.compile("^jar:file:([^!]+)!/$");

  /**
   * The class directory of an unpacked Spring Boot JAR, for example
   * <code>file:/app/BOOT-INF/classes/</code>. The group is the directory the JAR was
   * unpacked into.
   */
  private static final Pattern CLASSES_OF_AN_UNPACKED_JAR = Pattern.compile("^file:(.+/)BOOT-INF/classes/$");

  /**
   * A directory on the classpath, for example <code>file:/app/target/classes/</code>. The
   * group is its path.
   */
  private static final Pattern A_DIRECTORY = Pattern.compile("^file:(.+/)$");

  /**
   * The workflow module ID.
   */
  private final String id;

  /**
   * The classpath-root prefix (external URL form) of the JAR or directory the
   * workflow module descriptor was loaded from (the descriptor URL minus
   * {@link #METAINF_WORKFLOWMODULE}). Used to match classes originating from the
   * same JAR or directory, see {@link #isDeclaredInTheArtifactOf(String)} for why that
   * is more than comparing the two strings.
   */
  private final String sourceUri;

  /**
   * Workflow services associated to this workflow module.
   */
  private final Set<Class<?>> workflowServices = new HashSet<>();

  /**
   * Built while the marker files of the classpath are read, and by the builder of this
   * class in tests. Both values are known at that moment; the workflow services are not,
   * and are added later by the registration which pairs classes with modules.
   *
   * @param id The workflow module's id, the trimmed content of its marker file
   * @param sourceUri The classpath root the marker file came from, or <code>null</code>
   *     where it is unknown - a module without it matches no class by origin
   */
  public WorkflowModule(
      final String id,
      final String sourceUri) {

    this.id = id;
    this.sourceUri = sourceUri;

  }

  /**
   * Whether this module holds the given workflow service. Asked while a process service is
   * built, which is where the workflow module of a class has to be named - a class no
   * module claims ends the boot there.
   *
   * @param workflowService A workflow service class
   * @return Whether the workflow service class belongs to this workflow module
   */
  public boolean isWorkflowServiceKnown(
      final Class<?> workflowService) {

    return workflowServices.contains(workflowService);

  }

  /**
   * Whether this module's marker file belongs to the same JAR or directory as the given
   * classpath root.
   * <p>
   * Comparing the two roots as strings is not enough. Spring Boot packs an executable JAR
   * with the classes below <code>BOOT-INF/classes/</code>, but it leaves everything below
   * <code>META-INF/</code> at the top of the JAR. So in the JAR the marker file and the
   * classes next to it have different roots, while under <code>mvn spring-boot:run</code>
   * both share <code>target/classes/</code>. Two loaders also write the path of the JAR in
   * different ways, one with <code>%c3%bc</code> where the other writes <code>ü</code>.
   * This method therefore compares the JAR or directory each root stands for. A JAR nested
   * below <code>BOOT-INF/lib/</code> stays a JAR of its own.
   *
   * @param classpathRoot A classpath root as {@link WorkflowModuleAutoConfiguration#determineClasspathRootPrefix(Class)}
   *     returns it, or <code>null</code>
   * @return Whether the marker file of this module and the given root come from the same
   *     JAR or directory
   */
  public boolean isDeclaredInTheArtifactOf(
      final String classpathRoot) {

    if ((sourceUri == null) || (classpathRoot == null)) {
      return false;
    }
    return artifactOf(sourceUri).equals(artifactOf(classpathRoot));

  }

  /**
   * The JAR or directory a classpath root stands for, in one spelling for every loader.
   *
   * @param classpathRoot A classpath root
   * @return The decoded path of the JAR or directory, or the root itself where it has none
   *     of the known shapes
   */
  private static String artifactOf(
      final String classpathRoot) {

    for (final var shape : new Pattern[]{
        CLASSES_OF_A_NESTED_JAR, CLASSES_OF_A_CLASSIC_JAR, ROOT_OF_A_JAR
    }) {
      final var match = shape.matcher(classpathRoot);
      if (match.matches()) {
        return "jar:"
            + decoded(match.group(1));
      }
    }
    for (final var shape : new Pattern[]{
        CLASSES_OF_AN_UNPACKED_JAR, A_DIRECTORY
    }) {
      final var match = shape.matcher(classpathRoot);
      if (match.matches()) {
        return "directory:"
            + decoded(match.group(1));
      }
    }
    return classpathRoot;

  }

  /**
   * A path with its percent signs resolved. A plus sign stays a plus sign, because a path
   * in a URL does not use it for a space.
   *
   * @param path A path as it stands in a URL
   * @return The path as the file system names it
   */
  private static String decoded(
      final String path) {

    return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);

  }

  /**
   * Add the given workflow service class to the workflow module.
   *
   * @param workflowService The workflow service class
   */
  void addWorkflowService(
      final Class<?> workflowService) {

    workflowServices.add(workflowService);

  }

  /**
   * Add the given workflow service classes to the workflow module.
   *
   * @param workflowServices The workflow service classes
   */
  void addWorkflowServices(
      final Collection<Class<?>> workflowServices) {

    this.workflowServices.addAll(workflowServices);

  }

}
