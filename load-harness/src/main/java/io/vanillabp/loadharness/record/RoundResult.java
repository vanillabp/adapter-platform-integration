package io.vanillabp.loadharness.record;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * The numbers of one round. A round runs in a JVM of its own and writes them to a file, and
 * the comparison reads the files of all rounds afterwards.
 *
 * @param values The value of each metric the round measured
 */
public record RoundResult(
                          Map<Metric, Double> values) {

  /**
   * Keeps an unchangeable copy of the values.
   *
   * @param values The value of each metric
   */
  public RoundResult {

    values = Collections.unmodifiableMap(new EnumMap<>(values));

  }

  /**
   * The value of one metric.
   *
   * @param metric The metric
   * @return The value, or <code>null</code> where the round did not measure it
   */
  public Double value(
      final Metric metric) {

    return values.get(metric);

  }

  /**
   * Writes the round to a file, one line per metric.
   *
   * @param file Where to write
   * @throws IOException If the file cannot be written
   */
  public void write(
      final Path file) throws IOException {

    final var properties = new Properties();
    values.forEach((
        metric,
        value) -> properties.setProperty(metric.key(), Double.toString(value)));
    try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      properties.store(writer, "one round of the VanillaBP load run");
    }

  }

  /**
   * Reads a round from a file which {@link #write} wrote. A key nobody knows is left out, so
   * a round of a newer harness can be read by an older one.
   *
   * @param file The file
   * @return The round
   * @throws IOException If the file cannot be read, or a value is not a number
   */
  public static RoundResult read(
      final Path file) throws IOException {

    final var properties = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    final var values = new EnumMap<Metric, Double>(Metric.class);
    for (final var key : properties.stringPropertyNames()) {
      final var metric = Metric.ofKey(key);
      if (metric == null) {
        continue;
      }
      try {
        values.put(metric, Double.valueOf(properties.getProperty(key)));
      } catch (NumberFormatException e) {
        throw new IOException("The value of '%s' in %s is not a number: '%s'"
            .formatted(key, file, properties.getProperty(key)), e);
      }
    }
    return new RoundResult(values);

  }

}
