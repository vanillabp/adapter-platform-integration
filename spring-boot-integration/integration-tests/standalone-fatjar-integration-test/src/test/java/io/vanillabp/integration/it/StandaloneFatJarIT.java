package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which IS its workflow module, started the way it runs in production.
 * <p>
 * Spring Boot repackages the JAR: the classes move below <code>BOOT-INF/classes/</code>,
 * while <code>META-INF/workflow-module</code> stays at the top of the JAR. Under
 * <code>mvn spring-boot:run</code> both share one directory, so the BPMN files below
 * <code>processes/</code> are found. In the JAR they were not, and the application
 * deployed nothing. Each test starts the application in a process of its own and reads
 * what it printed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StandaloneFatJarIT {

  @Test
  @DisplayName("java -jar deploys the BPMN files below 'processes/'")
  public void theRepackagedJarDeploysTheBpmnOfTheApplication() throws Exception {

    final var output = startAndWaitForTheEnd(List.of("-jar", fatJar().toString()));

    assertTheBpmnBelowProcessesWasDeployed(output);

  }

  @Test
  @DisplayName("The JarLauncher in the unpacked JAR deploys the BPMN files below 'processes/'")
  public void theUnpackedJarDeploysTheBpmnOfTheApplication() throws Exception {

    final var unpacked = unpack(fatJar());

    final var output = startAndWaitForTheEnd(
        List.of("-cp", unpacked.toString(), "org.springframework.boot.loader.launch.JarLauncher"));

    assertTheBpmnBelowProcessesWasDeployed(output);

  }

  private static void assertTheBpmnBelowProcessesWasDeployed(
      final String output) {

    assertContains(output, "Started StandaloneTestApplication");
    assertContains(output, "STANDALONE-TEST module: standalone-module");
    // the application's own artifact is recognised, so the convention searches below
    // 'processes/' as well
    assertContains(output, "STANDALONE-TEST searched: classpath*:processes/dummy");
    assertContains(output, "Dummy-Adapter[dummy]: Deploying resources for standalone-module");
    assertContains(output, "Dummy-Adapter[dummy]: Starting workflow processing for standalone-module");

  }

  private static Path fatJar() {

    final var fatJarLocation = System.getProperty("fatjar.location");
    assertNotNull(fatJarLocation, "System property 'fatjar.location' not set!");
    final var fatJar = Path.of(fatJarLocation);
    assertTrue(Files.exists(fatJar), "Repackaged JAR not found: "
        + fatJarLocation);
    return fatJar;

  }

  private static Path unpack(
      final Path fatJar) throws IOException {

    final var unpackedLocation = System.getProperty("unpacked.location");
    assertNotNull(unpackedLocation, "System property 'unpacked.location' not set!");
    final var target = Path.of(unpackedLocation);
    try (final var jar = new ZipFile(fatJar.toFile())) {
      for (final var entry : java.util.Collections.list(jar.entries())) {
        final var file = target
            .resolve(entry.getName())
            .normalize();
        if (!file.startsWith(target)) {
          throw new IOException("Entry outside of the target directory: "
              + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(file);
          continue;
        }
        Files.createDirectories(file.getParent());
        try (final var content = jar.getInputStream(entry)) {
          Files.copy(content, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
    return target;

  }

  private static String startAndWaitForTheEnd(
      final List<String> arguments) throws Exception {

    final var command = new LinkedList<String>();
    command.add(ProcessHandle
        .current()
        .info()
        .command()
        .orElse("java"));
    // the JaCoCo agent, so the started application counts for the coverage (see pom.xml)
    final var jacocoAgent = System.getProperty("jacoco.agent");
    if ((jacocoAgent != null) && !jacocoAgent.isBlank() && !jacocoAgent.startsWith("$")) {
      command.add(jacocoAgent);
    }
    command.addAll(arguments);

    final var process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .start();
    final var output = new StringBuilder();
    try {
      final var outputReader = new Thread(() -> {
        try (final var reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            synchronized (output) {
              output.append(line).append('\n');
            }
          }
        } catch (IOException e) {
          // the stream closes when the process ends
        }
      });
      outputReader.start();

      // the application stops by itself once it has started (see StandaloneTestApplication)
      final var exited = process.waitFor(3, TimeUnit.MINUTES);
      assertTrue(exited, () -> "The application did not stop within 3 minutes! Captured output: "
          + capturedOutput(output));
      outputReader.join(TimeUnit.SECONDS.toMillis(10));
      assertEquals(0, process.exitValue(),
          () -> "The application failed! Captured output: "
              + capturedOutput(output));
      return capturedOutput(output);
    } finally {
      process.destroyForcibly();
    }

  }

  private static String capturedOutput(
      final StringBuilder output) {

    synchronized (output) {
      return output.toString();
    }

  }

  private static void assertContains(
      final String capturedOutput,
      final String expected) {

    assertTrue(capturedOutput.contains(expected),
        () -> "Expected '"
            + expected
            + "'. Captured output: "
            + capturedOutput);

  }

}
