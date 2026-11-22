package io.vanillabp.loadharness.record;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The history of the load run: one CSV file with a header and one row per week and platform.
 * A row per week appends cleanly, and a merge conflict can only hit a line somebody added by
 * hand.
 * <p>
 * The columns are read by their name in the header. So a column added later does not move
 * the old ones, and a row written before it existed reads as empty there.
 */
public final class LoadRecord {

  /**
   * The columns before the metrics, in this order.
   */
  public static final List<String> LEADING_COLUMNS = List
      .of("date", "candidate", "baseline", "platform", "runner", "cores", "workflows", "service.tasks", "verdict",
          "notes");

  /**
   * The five columns of each metric, after its key.
   */
  public static final List<String> METRIC_COLUMNS = List.of("baseline", "candidate", "ratio", "min", "max");

  private final List<String> header;

  private final List<Map<String, String>> rows;

  private LoadRecord(
      final List<String> header,
      final List<Map<String, String>> rows) {

    this.header = new ArrayList<>(header);
    this.rows = rows;

  }

  /**
   * The header a new record starts with.
   *
   * @return The names of all columns
   */
  public static List<String> currentHeader() {

    final var header = new ArrayList<>(LEADING_COLUMNS);
    for (final var metric : Metric.values()) {
      METRIC_COLUMNS.forEach(column -> header.add(metric.key()
          + "."
          + column));
    }
    return header;

  }

  /**
   * Reads a record. A file which does not exist is an empty record. A file which exists and
   * cannot be read is an error, never an empty record: a run which went on with a shortened
   * history would publish it.
   *
   * @param file The CSV file
   * @return The record
   * @throws IOException If the file exists and cannot be read, or a row has the wrong number
   *           of fields
   */
  public static LoadRecord read(
      final Path file) throws IOException {

    if (!Files.exists(file)) {
      return new LoadRecord(currentHeader(), new ArrayList<>());
    }
    final var lines = Files
        .readAllLines(file, StandardCharsets.UTF_8)
        .stream()
        .filter(line -> !line.isBlank())
        .toList();
    if (lines.isEmpty()) {
      return new LoadRecord(currentHeader(), new ArrayList<>());
    }
    final var header = List.of(lines.getFirst().split(",", -1));
    if (!header.containsAll(LEADING_COLUMNS)) {
      throw new IOException("The first line of %s is not the header of a load record: %s"
          .formatted(file, lines.getFirst()));
    }
    final var rows = new ArrayList<Map<String, String>>();
    for (var index = 1; index < lines.size(); index++) {
      final var fields = lines.get(index).split(",", -1);
      if (fields.length != header.size()) {
        throw new IOException("Line %d of %s has %d fields, but the header names %d"
            .formatted(index + 1, file, fields.length, header.size()));
      }
      final var row = new LinkedHashMap<String, String>();
      for (var column = 0; column < fields.length; column++) {
        row.put(header.get(column), fields[column]);
      }
      rows.add(row);
    }
    return new LoadRecord(header, rows);

  }

  /**
   * The newest row of a platform.
   *
   * @param platform The platform, as the run names it
   * @return The row, or nothing where the platform has none yet
   */
  public Optional<Map<String, String>> lastRowOf(
      final String platform) {

    for (var index = rows.size() - 1; index >= 0; index--) {
      if (platform.equals(rows.get(index).get("platform"))) {
        return Optional.of(rows.get(index));
      }
    }
    return Optional.empty();

  }

  /**
   * All rows, oldest first.
   *
   * @return The rows, each by column name
   */
  public List<Map<String, String>> rows() {

    return List.copyOf(rows);

  }

  /**
   * Adds a row. A column which the header of this file does not have yet is added at the end
   * of the header, and every older row stays empty there.
   *
   * @param row The row, by column name
   */
  public void append(
      final Map<String, String> row) {

    row
        .keySet()
        .stream()
        .filter(column -> !header.contains(column))
        .forEach(header::add);
    rows.add(new LinkedHashMap<>(row));

  }

  /**
   * Writes the record. It is written next to the file first and then moved over it, so a
   * reader never sees half a file.
   *
   * @param file The CSV file
   * @throws IOException If the file cannot be written
   */
  public void write(
      final Path file) throws IOException {

    final var lines = new ArrayList<String>();
    lines.add(String.join(",", header));
    for (final var row : rows) {
      lines
          .add(String
              .join(",", header
                  .stream()
                  .map(column -> row.getOrDefault(column, ""))
                  .toList()));
    }
    final var parent = file.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    final var temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
    Files.write(temporary, lines, StandardCharsets.UTF_8);
    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

  }

  /**
   * How a number is written into the record: an integer where it is one, otherwise with up
   * to four decimals, and empty where it is not a number.
   *
   * @param value The number
   * @return The text of the field
   */
  public static String format(
      final double value) {

    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return "";
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return Long.toString((long) value);
    }
    return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");

  }

}
