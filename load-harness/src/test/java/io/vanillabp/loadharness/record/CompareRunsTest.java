package io.vanillabp.loadharness.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The command which the weekly workflow runs: it writes a row in every week, red ones
 * included, and writes nothing where it cannot read the history.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CompareRunsTest {

  @TempDir
  Path directory;

  private Path rounds(
      final String side,
      final double statementsPerStart,
      final double... wallMillis) throws Exception {

    final var sideDirectory = Files.createDirectories(directory.resolve(side));
    for (var index = 0; index < wallMillis.length; index++) {
      final var values = new EnumMap<Metric, Double>(Metric.class);
      values.put(Metric.STATEMENTS_START, statementsPerStart);
      values.put(Metric.WALL_TIME, wallMillis[index]);
      new RoundResult(values).write(sideDirectory.resolve("round-%d.properties".formatted(index)));
    }
    return sideDirectory;

  }

  private int compare(
      final Path record,
      final Path baseline,
      final Path candidate,
      final String date) {

    return CompareRuns
        .run(new String[]{
            "--record", record.toString(), "--platform", "spring-boot", "--baseline-rounds", baseline
                .toString(), "--candidate-rounds", candidate
                    .toString(), "--date", date, "--candidate-commit", "abc1234", "--baseline-ref", "def5678", "--runner", "Some CPU, 2 GHz", "--cores", "4", "--workflows", "1000", "--service-tasks", "3", "--verdict", directory
                        .resolve("verdict.md").toString()
        });

  }

  @Test
  @DisplayName("A green week appends one row with medians, ratio and the candidate's range")
  public void aGreenWeekAppendsARow() throws Exception {

    final var record = directory.resolve("load/spring-boot.csv");
    final var exit = compare(
        record, rounds("baseline", 5, 1000, 1100, 900), rounds("candidate", 5, 1000, 1050, 950), "2026-11-22");

    assertEquals(0, exit);
    final var rows = LoadRecord.read(record).rows();
    assertEquals(1, rows.size());
    final var row = rows.getFirst();
    assertEquals("green", row.get("verdict"));
    assertEquals("1000", row.get("wall.ms.baseline"));
    assertEquals("1000", row.get("wall.ms.candidate"));
    assertEquals("1", row.get("wall.ms.ratio"));
    assertEquals("950", row.get("wall.ms.min"));
    assertEquals("1050", row.get("wall.ms.max"));
    assertEquals("5", row.get("statements.start.candidate"));
    // the comma of the runner's name would break the CSV
    assertEquals("Some CPU  2 GHz", row.get("runner"));
    assertTrue(Files.readString(directory.resolve("verdict.md")).contains("green"));

  }

  @Test
  @DisplayName("A red week writes its row as well, marked red, and exits with 2")
  public void aRedWeekIsWrittenToo() throws Exception {

    final var record = directory.resolve("spring-boot.csv");
    final var exit = compare(record, rounds("baseline", 5, 1000), rounds("candidate", 6, 1000), "2026-11-22");

    assertEquals(CompareRuns.RED, exit);
    final var rows = LoadRecord.read(record).rows();
    assertEquals("red", rows.getFirst().get("verdict"));
    assertTrue(rows.getFirst().get("notes").contains("statements.start"));

  }

  @Test
  @DisplayName("The week after a slow week reads the row before and turns red when it is slow too")
  public void theSecondSlowWeekIsRed() throws Exception {

    final var record = directory.resolve("spring-boot.csv");
    assertEquals(0, compare(record, rounds("b1", 5, 1000), rounds("c1", 5, 1200), "2026-11-15"));
    assertEquals("note", LoadRecord.read(record).rows().getFirst().get("verdict"));

    assertEquals(CompareRuns.RED, compare(record, rounds("b2", 5, 1000), rounds("c2", 5, 1150), "2026-11-22"));
    assertEquals(2, LoadRecord.read(record).rows().size());

  }

  @Test
  @DisplayName("A record which cannot be read is left alone, and nothing is written")
  public void anUnreadableRecordWritesNothing() throws Exception {

    final var record = directory.resolve("spring-boot.csv");
    Files.write(record, List.of(String.join(",", LoadRecord.currentHeader()), "2026-11-15,too,few"),
        StandardCharsets.UTF_8);
    final var before = Files.readString(record);

    final var exit = compare(record, rounds("baseline", 5, 1000), rounds("candidate", 5, 1000), "2026-11-22");

    assertEquals(CompareRuns.NOTHING_WRITTEN, exit);
    assertEquals(before, Files.readString(record));

  }

  @Test
  @DisplayName("A missing option writes nothing")
  public void aMissingOptionWritesNothing() {

    assertEquals(CompareRuns.NOTHING_WRITTEN, CompareRuns.run(new String[]{
        "--platform", "quarkus"
    }));
    assertFalse(Files.exists(directory.resolve("spring-boot.csv")));

  }

  @Test
  @DisplayName("A column added later goes to the end, and the older rows stay empty there")
  public void aNewColumnGoesToTheEnd() throws Exception {

    final var record = directory.resolve("spring-boot.csv");
    final var older = LoadRecord.read(record);
    older.append(java.util.Map.of("date", "2026-11-08", "platform", "spring-boot", "verdict", "green"));
    older.write(record);

    assertEquals(0, compare(record, rounds("baseline", 5, 1000), rounds("candidate", 5, 1000), "2026-11-22"));
    final var rows = LoadRecord.read(record).rows();
    assertEquals(2, rows.size());
    assertEquals("", rows.getFirst().get("wall.ms.ratio"));
    assertEquals("1", rows.get(1).get("wall.ms.ratio"));

  }

}
