package io.vanillabp.integration.test.workflowmodule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;

/**
 * Spring Boot packs the classes of an executable JAR below <code>BOOT-INF/classes/</code>
 * but leaves <code>META-INF/workflow-module</code> at the top of the JAR. The URLs below
 * were read from a running application, one per way of starting it. The marker file has to
 * be found in the same artifact as the application's classes in each of them, or the
 * module's BPMN files are searched in the wrong place.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MarkerFileOfAnExecutableJarTest {

  @Test
  @DisplayName("java -jar with the loader of Spring Boot 3.2 and later")
  public void theMarkerFileBelongsToTheClassesOfANestedJar() {

    final var module = moduleDeclaredIn("jar:file:/srv/app/loan-approval.jar!/");

    assertTrue(module.isDeclaredInTheArtifactOf("jar:nested:/srv/app/loan-approval.jar/!BOOT-INF/classes/!/"));

  }

  @Test
  @DisplayName("java -jar with the loader of Spring Boot before 3.2")
  public void theMarkerFileBelongsToTheClassesOfAClassicJar() {

    final var module = moduleDeclaredIn("jar:file:/srv/app/loan-approval.jar!/");

    assertTrue(module.isDeclaredInTheArtifactOf("jar:file:/srv/app/loan-approval.jar!/BOOT-INF/classes!/"));

  }

  @Test
  @DisplayName("JarLauncher started in the directory the JAR was unpacked into")
  public void theMarkerFileBelongsToTheClassesOfAnUnpackedJar() {

    final var module = moduleDeclaredIn("file:/srv/app/");

    assertTrue(module.isDeclaredInTheArtifactOf("file:/srv/app/BOOT-INF/classes/"));

  }

  @Test
  @DisplayName("Both loaders spell the path of the JAR differently")
  public void aPathIsComparedAfterItsPercentSignsAreResolved() {

    // what the JDK and the loader of Spring Boot made of the directory 'sp ace+ü'
    final var module = moduleDeclaredIn("jar:file:/srv/sp%20ace+%c3%bc/loan-approval.jar!/");

    assertTrue(module.isDeclaredInTheArtifactOf("jar:nested:/srv/sp%20ace+ü/loan-approval.jar/!BOOT-INF/classes/!/"));

  }

  @Test
  @DisplayName("A JAR below BOOT-INF/lib is an artifact of its own")
  public void aNestedLibraryIsNotTheApplication() {

    final var module = moduleDeclaredIn("jar:file:/srv/app/loan-approval.jar!/");

    assertFalse(module.isDeclaredInTheArtifactOf("jar:nested:/srv/app/loan-approval.jar/!BOOT-INF/lib/a-module.jar!/"));

  }

  @Test
  @DisplayName("Two class directories of one Maven module are two artifacts")
  public void testClassesAreNotTheClassesOfTheApplication() {

    final var module = moduleDeclaredIn("file:/work/app/target/classes/");

    assertTrue(module.isDeclaredInTheArtifactOf("file:/work/app/target/classes/"));
    assertFalse(module.isDeclaredInTheArtifactOf("file:/work/app/target/test-classes/"));

  }

  @Test
  @DisplayName("An unknown root matches nothing")
  public void anUnknownRootMatchesNothing() {

    final var module = moduleDeclaredIn("jar:file:/srv/app/loan-approval.jar!/");

    assertFalse(module.isDeclaredInTheArtifactOf(null));
    assertFalse(new WorkflowModule("loan-approval", null).isDeclaredInTheArtifactOf("file:/srv/app/"));

  }

  private static WorkflowModule moduleDeclaredIn(
      final String rootOfTheMarkerFile) {

    return new WorkflowModule("loan-approval", rootOfTheMarkerFile);

  }

}
