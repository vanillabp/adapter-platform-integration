package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Proves that a Spring Boot repackaged (fat) JAR works. Since Spring Boot 3.2, the
 * loader uses the {@code jar:nested:} protocol for classes of nested JARs. So the
 * matching of workflow modules must not rely on the {@code file:} or {@code jar:file:}
 * URL of the class' protection domain. The spring-boot-maven-plugin builds the
 * repackaged JAR in the package phase. The test starts it as a separate process and
 * checks:
 * <ul>
 *   <li>the application starts (with nested JARs of Spring Boot 3.2 and later, this
 *       failed with a NullPointerException until the matching stopped depending on
 *       the class loader),</li>
 *   <li>each process service reports the workflow module of the nested JAR its
 *       workflow service was loaded from,</li>
 *   <li>BPMN resources are deployed and workflow processing is started, and</li>
 *   <li>the graceful-shutdown path stops the process services.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class FatJarIT {

  @Test
  @DisplayName("Repackaged application starts and process services report their workflow modules")
  public void fatJarStartsAndProcessServicesReportTheirWorkflowModules() throws Exception {

    final var fatJarLocation = System.getProperty("fatjar.location");
    assertNotNull(fatJarLocation, "System property 'fatjar.location' not set!");
    assertTrue(Files.exists(Path.of(fatJarLocation)),
        "Repackaged JAR not found: "
            + fatJarLocation);

    final var javaExecutable = ProcessHandle
        .current()
        .info()
        .command()
        .orElse("java");

    final var command = new LinkedList<String>();
    command.add(javaExecutable);
    // the JVM argument which records coverage, see <systemPropertyVariables> in pom.xml
    final var jacocoAgent = System.getProperty("jacoco.agent");
    if ((jacocoAgent != null) && !jacocoAgent.isBlank() && !jacocoAgent.startsWith("$")) {
      command.add(jacocoAgent);
    }
    command.add("-jar");
    command.add(fatJarLocation);

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
        } catch (Exception e) {
          // stream closed on process exit
        }
      });
      outputReader.start();

      // the application shuts down by itself after the start (see FatJarTestApplication)
      final var exited = process.waitFor(3, TimeUnit.MINUTES);
      assertTrue(exited, () -> "Repackaged application did not exit within 3 minutes! Captured output: "
          + capturedOutput(output));
      outputReader.join(TimeUnit.SECONDS.toMillis(10));

      assertEquals(0, process.exitValue(),
          () -> "Repackaged application failed! Captured output: "
              + capturedOutput(output));

      final var capturedOutput = capturedOutput(output);
      assertContains(capturedOutput, "Started FatJarTestApplication");
      // workflow services of nested JARs are matched to their workflow modules
      assertContains(capturedOutput, "FATJAR-TEST sample: test-module");
      assertContains(capturedOutput, "FATJAR-TEST multibpmn1: multi-bpmn-module");
      assertContains(capturedOutput, "FATJAR-TEST multibpmn2: multi-bpmn-module");
      // BPMN resources found in the fat JAR were deployed and processing started
      assertContains(capturedOutput, "Dummy-Adapter[test]: Deploying resources for test-module");
      assertContains(capturedOutput, "Dummy-Adapter[test]: Starting workflow processing for test-module");
      // graceful shutdown stops the process services
      assertContains(capturedOutput, "Stopping process service: test-module");
      assertContains(capturedOutput, "Stopping process service: multi-bpmn-module");
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
