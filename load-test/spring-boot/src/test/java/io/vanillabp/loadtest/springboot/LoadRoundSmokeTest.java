package io.vanillabp.loadtest.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.loadharness.record.Metric;
import io.vanillabp.loadharness.record.RoundResult;

/**
 * Runs a tiny round on H2, so the build proves that the application, the double and the
 * harness fit together. It measures nothing worth keeping.
 */
@ExtendWith(SuppressOutputExtension.class)
public class LoadRoundSmokeTest {

  @TempDir
  Path directory;

  @Test
  @DisplayName("A tiny round runs every workflow to its end and counts its statements")
  public void aTinyRoundRuns() throws Exception {

    final var output = directory.resolve("round.properties");
    try (var context = SpringApplication
        .run(
            LoadTestApplication.class,
            "--load.output="
                + output,
            "--load.workflows=5",
            "--load.counting-workflows=3",
            "--load.timeout=PT1M")) {
      assertTrue(LoadTestApplication.runRound(context));
    }

    final var round = RoundResult.read(output);
    assertEquals(0.0, round.value(Metric.WORKFLOWS_FAILED), "every workflow ran to its end");
    assertEquals(0.0, round.value(Metric.OUTBOX_DISCARDED));
    assertEquals(0.0, round.value(Metric.OUTBOX_BLOCKED));
    assertTrue(round.value(Metric.STATEMENTS_START) > 0, "a start writes at least the outbox row");
    assertTrue(round.value(Metric.STATEMENTS_COMPLETION) > 0);
    assertTrue(round.value(Metric.STATEMENTS_CORRELATION) > 0);
    assertTrue(round.value(Metric.WALL_TIME) > 0);
    // the meters of VanillaBP reach the registry the round reads
    assertTrue(round.value(Metric.OUTBOX_WAIT) > 0, "the outbox reported how long its entries waited");

  }

}
