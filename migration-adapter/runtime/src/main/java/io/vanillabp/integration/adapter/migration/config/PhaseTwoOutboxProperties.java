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
   * How long to wait after a failed dispatch until the entry is retried.
   */
  @Builder.Default
  private Duration attemptFrequency = Duration.ofSeconds(30);

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   * Blocked entries have to be fixed manually (e.g. by cleaning up the outbox store).
   */
  @Builder.Default
  private int blockAfterAttempts = 10;

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
   * The very same number decides something else on the INBOUND side, where it is a
   * correctness setting: a task delivery arriving later than the retention finds no
   * record and runs the business code a second time (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). Shortening it to keep the
   * outbox small therefore costs something there.
   */
  @Builder.Default
  private Duration retention = Duration.ofDays(7);

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
