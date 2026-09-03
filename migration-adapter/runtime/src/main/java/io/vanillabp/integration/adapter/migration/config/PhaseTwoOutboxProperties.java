package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of the default {@link io.vanillabp.integration.spi.PhaseTwoOutbox}
 * implementations (properties section <code>vanillabp.outbox</code>) - the single
 * source of truth for keys, defaults and documentation, used by all platform
 * implementations (Spring Boot: gruelbox-based JPA and MongoDB; Quarkus: JDBC/Agroal
 * and MongoDB).
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class PhaseTwoOutboxProperties {

  /**
   * The fixed delay between two background polls for committed-but-unprocessed outbox
   * entries. Polling is required for crash recovery and retries; right after a commit
   * the entry is dispatched immediately (independently of this delay).
   */
  @Builder.Default
  private Duration pollInterval = Duration.ofSeconds(10);

  /**
   * The distance to the FIRST retry after a failed dispatch. Every further attempt
   * doubles it until {@link #maxAttemptFrequency} is reached (see
   * {@link #attemptDelay(int)}).
   */
  @Builder.Default
  private Duration attemptFrequency = Duration.ofSeconds(30);

  /**
   * The longest distance the growing backoff reaches. Five minutes, so a BPMS which
   * comes back is noticed within five minutes however long it was away.
   */
  @Builder.Default
  private Duration maxAttemptFrequency = Duration.ofMinutes(5);

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   * Fifty of them, which with the two defaults above span an outage of about four
   * hours - a cluster upgrade rather than an exotic event. What ends up blocked is
   * then an entry which is broken rather than one whose BPMS was away for a while,
   * and it is the case an operator has to look at.
   */
  @Builder.Default
  private int blockAfterAttempts = 50;

  /**
   * The distance to the next attempt after a dispatch which failed, doubling per
   * attempt and capped at {@link #maxAttemptFrequency}. The first retry keeps
   * {@link #attemptFrequency}, because most failures are momentary and waiting
   * longer buys nothing there.
   * <p>
   * The stores VanillaBP owns compute their next attempt with this method, so the
   * curve is the same on every platform and on every persistence. Gruelbox brings a
   * retry policy of its own which knows one fixed distance, so an application on that
   * store keeps the behaviour it always had - the per-store table of the platform
   * pages owns that difference.
   *
   * @param attemptsSoFar The number of attempts already made, zero before the first
   *        retry
   * @return The distance to the next attempt
   */
  public Duration attemptDelay(
      final int attemptsSoFar) {

    if (attemptsSoFar <= 0) {
      return attemptFrequency;
    }
    // doubling in the exponent rather than in a loop, and bounded before it is
    // computed: 2^attempts overflows a long at 63 attempts, and blockAfterAttempts is
    // configurable
    if (attemptsSoFar >= 62) {
      return maxAttemptFrequency;
    }
    final var doubled = attemptFrequency.multipliedBy(1L << attemptsSoFar);
    return doubled.compareTo(maxAttemptFrequency) > 0 ? maxAttemptFrequency : doubled;

  }

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically. Disable this if the database schema is managed manually (e.g. by
   * Flyway or Liquibase).
   */
  @Builder.Default
  private boolean createSchema = true;

  /**
   * How long successfully dispatched entries (marked as DONE) are retained before
   * they are deleted asynchronously - what a retained entry buys is a dispatched
   * operation somebody can still look at during support, not a longer deduplication
   * window: that one ends with the dispatch (see
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox}).
   * <p>
   * This is the OUTBOX half only. The records of processed task deliveries have a
   * retention of their own (<code>vanillabp.delivery.retention</code>), which defaults to
   * this number and is a correctness setting rather than an operational one: a delivery
   * arriving later than it finds no record and runs the business code a second time (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). The two were one property until
   * they were told apart (decision 24 in the repository's DECISIONS.md), which is why
   * shortening this one to keep the outbox table small used to shorten a correctness window
   * with the same hand.
   */
  @Builder.Default
  private Duration retention = DEFAULT_RETENTION;

  /**
   * The default of {@link #retention}, and therefore the default of
   * <code>vanillabp.delivery.retention</code>, which follows it where it is not set
   * itself. Seven days.
   */
  public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

  /**
   * Configuration of the JDBC-based default outbox (Spring Boot: gruelbox over the
   * data source; Quarkus: the Agroal/JDBC implementation). Both default outboxes
   * (JDBC and MongoDB) may be active in the same application - each aggregate is
   * served by the outbox matching its persistence.
   */
  @Builder.Default
  private JdbcOutboxProperties jdbc = new JdbcOutboxProperties();

  /**
   * Configuration of the MongoDB-based default outbox. Both default outboxes (JDBC
   * and MongoDB) may be active in the same application - each aggregate is served by
   * the outbox matching its persistence.
   */
  @Builder.Default
  private MongoOutboxProperties mongo = new MongoOutboxProperties();

  @Getter
  @Setter
  @NoArgsConstructor
  @SuperBuilder
  public static class JdbcOutboxProperties {

    /**
     * Whether the JDBC-based default outbox is created when a data source is
     * available. Disable it if the application defines its own
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} bean and the
     * default (including its store and background dispatcher) is unwanted.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * The name of the table storing outbox entries. Every outbox instance needs its
     * own store - two dispatchers polling the same table would compete and
     * double-dispatch. <code>null</code> means the platform's default table name
     * (Spring Boot/gruelbox: <code>TXNO_OUTBOX</code>; Quarkus:
     * <code>VANILLABP_PHASE_TWO_OUTBOX</code>). NOTE: on Spring Boot the gruelbox
     * schema migration always targets the default table - a custom name requires
     * the table (structured like <code>TXNO_OUTBOX</code>) to be created manually,
     * which is verified at startup.
     */
    @Builder.Default
    private String table = null;

  }

  @Getter
  @Setter
  @NoArgsConstructor
  @SuperBuilder
  public static class MongoOutboxProperties {

    /**
     * Whether the MongoDB-based default outbox is created when a MongoDB connection
     * is available. Disable it if the application defines its own
     * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} bean and the
     * default (including its store and background dispatcher) is unwanted.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * The name of the collection storing outbox entries. Every outbox instance
     * needs its own store - two dispatchers polling the same collection would
     * compete and double-dispatch.
     */
    @Builder.Default
    private String collection = "vanillabp-phase-two-outbox";

  }

}
