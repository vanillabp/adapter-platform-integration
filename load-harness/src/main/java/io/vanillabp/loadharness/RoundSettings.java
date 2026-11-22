package io.vanillabp.loadharness;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * What one round runs, read from the configuration of the application. Both platforms read
 * the same keys, so the weekly run passes the same system properties to both:
 *
 * <table>
 * <caption>The keys of a round</caption>
 * <tr><th>Key</th><th>Default</th><th>Meaning</th></tr>
 * <tr><td><code>load.output</code></td><td>none</td><td>The file the round writes its numbers
 * to. Without it the application starts and does nothing</td></tr>
 * <tr><td><code>load.workflows</code></td><td>100</td><td>The first size of the live set. The
 * second size, which is also timed, is ten times as much</td></tr>
 * <tr><td><code>load.counting-workflows</code></td><td>20</td><td>The small work which warms up
 * and counts statements</td></tr>
 * <tr><td><code>load.service-tasks</code></td><td>3</td><td>Service tasks per workflow</td></tr>
 * <tr><td><code>load.slots</code></td><td>4</td><td>Pieces of work at the same time</td></tr>
 * <tr><td><code>load.phase-two-delay</code></td><td>PT0S</td><td>How long the BPMS takes to
 * answer one phase-two call</td></tr>
 * <tr><td><code>load.timeout</code></td><td>PT5M</td><td>How long one of the three works may
 * take</td></tr>
 * </table>
 *
 * @param output Where the round writes its numbers, or <code>null</code> to run nothing
 * @param work The work, with the first size as its number of workflows
 * @param sizes The three sizes
 * @param timeout How long one of the three works may take
 */
public record RoundSettings(
                            Path output,
                            FixedWork work,
                            LoadRound.Sizes sizes,
                            Duration timeout) {

  /**
   * The factor between the two sizes of the live set.
   */
  public static final int SECOND_SIZE_FACTOR = 10;

  /**
   * Reads the settings.
   *
   * @param configuration Answers a key with its value, or with nothing where it is not set
   * @return The settings
   */
  public static RoundSettings from(
      final Function<String, Optional<String>> configuration) {

    final var output = configuration.apply("load.output").map(Path::of).orElse(null);
    final var workflows = configuration.apply("load.workflows").map(Integer::valueOf).orElse(100);
    final var counting = configuration.apply("load.counting-workflows").map(Integer::valueOf).orElse(20);
    final var serviceTasks = configuration.apply("load.service-tasks").map(Integer::valueOf).orElse(3);
    final var slots = configuration.apply("load.slots").map(Integer::valueOf).orElse(FixedWork.DEFAULT_SLOTS);
    final var delay = configuration.apply("load.phase-two-delay").map(Duration::parse).orElse(Duration.ZERO);
    final var timeout = configuration.apply("load.timeout").map(Duration::parse).orElse(Duration.ofMinutes(5));
    return new RoundSettings(
        output, new FixedWork(workflows, serviceTasks, slots, delay), new LoadRound.Sizes(counting, workflows, workflows * SECOND_SIZE_FACTOR), timeout);

  }

  /**
   * Runs the round and writes its numbers, if an output is configured.
   *
   * @param application The application to drive
   * @param driver The driver, which the application registered as a hook of the double
   * @return Whether a round ran
   * @throws java.io.IOException If the numbers cannot be written
   */
  public boolean runAndWrite(
      final LoadApplication application,
      final LoadDriver driver) throws java.io.IOException {

    if (output == null) {
      return false;
    }
    final var result = LoadRound.run(application, driver, work, sizes, timeout);
    final var parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      java.nio.file.Files.createDirectories(parent);
    }
    result.write(output);
    return true;

  }

}
