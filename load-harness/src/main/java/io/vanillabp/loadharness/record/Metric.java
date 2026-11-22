package io.vanillabp.loadharness.record;

/**
 * Every number the load run keeps, with what it means for the verdict. The order is the
 * order of the columns in the record. A new metric goes at the end, so the columns of the
 * weeks before keep their place.
 */
public enum Metric {

  /**
   * Statements one workflow start runs in the application's transaction.
   */
  STATEMENTS_START("statements.start", Kind.STATEMENTS),

  /**
   * Statements one delivery of a service task runs.
   */
  STATEMENTS_TASK("statements.task", Kind.STATEMENTS),

  /**
   * Statements the completion of a task runs which the application completes later.
   */
  STATEMENTS_COMPLETION("statements.completion", Kind.STATEMENTS),

  /**
   * Statements one message correlation runs in the application's transaction.
   */
  STATEMENTS_CORRELATION("statements.correlation", Kind.STATEMENTS),

  /**
   * How long the timed work took, in milliseconds.
   */
  WALL_TIME("wall.ms", Kind.TIME),

  /**
   * How long an outbox entry waited on average, from its commit to the end of its dispatch,
   * in milliseconds.
   */
  OUTBOX_WAIT("outbox.wait.ms", Kind.TIME),

  /**
   * The age of the oldest waiting outbox entry, at its highest, in seconds.
   */
  OLDEST_WAITING("outbox.oldest.s", Kind.INFO),

  /**
   * Connections in use, at the highest.
   */
  CONNECTIONS_ACTIVE("connections.active", Kind.INFO),

  /**
   * Threads which waited for a connection, at the highest.
   */
  CONNECTIONS_PENDING("connections.pending", Kind.INFO),

  /**
   * Bytes of the live set per workflow between the two sizes of the work. Close to zero
   * when nothing leaks.
   */
  LIVE_SET_PER_WORKFLOW("liveset.bytes.per.workflow", Kind.INFO),

  /**
   * The share of the wall time the JVM spent in garbage collection.
   */
  GC_SHARE("gc.share", Kind.INFO),

  /**
   * Repeated deliveries which ran the handler a second time.
   */
  REDELIVERIES_CONCURRENT("redeliveries.concurrent", Kind.ZERO),

  /**
   * Operations the outbox refused because an equal one was still waiting.
   */
  OUTBOX_DISCARDED("outbox.discarded", Kind.ZERO),

  /**
   * Outbox entries a store gave up on.
   */
  OUTBOX_BLOCKED("outbox.blocked", Kind.ZERO),

  /**
   * Task deliveries with the outcome failed.
   */
  DELIVERIES_FAILED("deliveries.failed", Kind.ZERO),

  /**
   * Workflows of the harness which failed or did not finish in time.
   */
  WORKFLOWS_FAILED("workflows.failed", Kind.ZERO);

  /**
   * What a metric means for the verdict.
   */
  public enum Kind {

    /**
     * A count of statements. It does not move with the runner, so any change is red at
     * once.
     */
    STATEMENTS,

    /**
     * A time. Red only when the ratio of the medians missed the threshold in two weeks in a
     * row, because the noise of a shared runner reaches one week's threshold on its own.
     */
    TIME,

    /**
     * Has to be zero in the candidate. A run which finished its work with one of these did
     * not pass, however fast it was.
     */
    ZERO,

    /**
     * Kept in the record and never judged.
     */
    INFO

  }

  private final String key;

  private final Kind kind;

  Metric(
      final String key,
      final Kind kind) {

    this.key = key;
    this.kind = kind;

  }

  /**
   * The name of the metric in a round file and in the columns of the record.
   *
   * @return The key
   */
  public String key() {

    return key;

  }

  /**
   * What the metric means for the verdict.
   *
   * @return The kind
   */
  public Kind kind() {

    return kind;

  }

  /**
   * The metric of a key.
   *
   * @param key The key of a round file
   * @return The metric, or <code>null</code> for a key nobody knows
   */
  public static Metric ofKey(
      final String key) {

    for (final var metric : values()) {
      if (metric.key.equals(key)) {
        return metric;
      }
    }
    return null;

  }

}
