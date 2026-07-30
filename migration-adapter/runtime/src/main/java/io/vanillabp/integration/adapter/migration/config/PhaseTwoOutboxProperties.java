package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of the default {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}
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
   * they are deleted asynchronously. Retained entries keep the deduplication window
   * of the idempotency contract open beyond dispatch (see
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}).
   */
  @Builder.Default
  private Duration retention = Duration.ofDays(7);

}
