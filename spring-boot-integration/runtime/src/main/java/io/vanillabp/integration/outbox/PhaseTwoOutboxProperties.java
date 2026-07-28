package io.vanillabp.integration.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration of the default {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}
 * implementations (prefix <code>vanillabp.outbox</code>). Used by the gruelbox-based
 * JPA implementation as well as the MongoDB implementation.
 */
@ConfigurationProperties(prefix = PhaseTwoOutboxProperties.PREFIX)
@Getter
@Setter
public class PhaseTwoOutboxProperties {

  public static final String PREFIX = "vanillabp.outbox";

  /**
   * The fixed delay between two background polls for committed-but-unprocessed outbox
   * entries. Polling is required for crash recovery and retries; right after a commit
   * the entry is dispatched immediately (independently of this delay).
   */
  private Duration pollInterval = Duration.ofSeconds(10);

  /**
   * How long to wait after a failed dispatch until the entry is retried.
   */
  private Duration attemptFrequency = Duration.ofSeconds(30);

  /**
   * After how many failed attempts an entry is blocked (not retried any longer).
   * Blocked entries have to be fixed manually (e.g. by cleaning up the outbox store).
   */
  private int blockAfterAttempts = 10;

  /**
   * Whether the schema (table/collection) used to store outbox entries is created
   * automatically. Disable this if the database schema is managed manually (e.g. by
   * Flyway or Liquibase).
   */
  private boolean createSchema = true;

  /**
   * How long successfully dispatched entries (marked as DONE) are retained before
   * they are deleted asynchronously. Retained entries keep the deduplication window
   * of the idempotency contract open beyond dispatch (see
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox}).
   */
  private Duration retention = Duration.ofDays(7);

}
