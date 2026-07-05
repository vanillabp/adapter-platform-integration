package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Resilience settings used by the migration adapter when talking to BPMSs providing
 * eventual consistency (e.g. remote BPMS): failed or undecidable calls (see
 * {@link io.vanillabp.integration.adapter.spi.WorkflowAwareness#BPMS_UNAVAILABLE})
 * are retried using an exponential backoff.
 * <p>
 * Resolvable on the same three override levels as <code>prioritized-adapters</code>:
 * global (<code>vanillabp.resilience</code>), workflow module
 * (<code>vanillabp.workflow-modules.&lt;id&gt;.resilience</code>) and workflow
 * (<code>...workflows.&lt;bpmnProcessId&gt;.resilience</code>) - the most specific
 * block configured wins as a whole. Values not set within the winning block fall
 * back to the defaults.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ResilienceProperties {

  public static final int DEFAULT_MAX_RETRIES = 3;

  public static final Duration DEFAULT_INITIAL_INTERVAL = Duration.ofSeconds(1);

  public static final double DEFAULT_MULTIPLIER = 2.0;

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The maximum number of retries after the initial attempt failed.
   */
  private Integer maxRetries;

  /**
   * The backoff interval before the first retry. Subsequent retries multiply the
   * previous interval by {@link #multiplier}.
   */
  private Duration initialInterval;

  /**
   * The multiplier applied to the backoff interval for each subsequent retry.
   */
  private Double multiplier;

  /**
   * The timeout per adapter call. Calls exceeding the timeout are treated as
   * &quot;BPMS unavailable&quot;.
   */
  private Duration timeout;

  /**
   * Builds the effective resilience settings: values not set within the given block
   * (or the whole block being absent) fall back to the defaults.
   *
   * @param configured The configured block or <code>null</code> if none was configured
   * @return A fully populated instance
   */
  public static ResilienceProperties effective(
      final ResilienceProperties configured) {

    final var result = new ResilienceProperties();
    result.setMaxRetries(
        (configured != null) && (configured.getMaxRetries() != null)
            ? configured.getMaxRetries()
            : DEFAULT_MAX_RETRIES);
    result.setInitialInterval(
        (configured != null) && (configured.getInitialInterval() != null)
            ? configured.getInitialInterval()
            : DEFAULT_INITIAL_INTERVAL);
    result.setMultiplier(
        (configured != null) && (configured.getMultiplier() != null)
            ? configured.getMultiplier()
            : DEFAULT_MULTIPLIER);
    result.setTimeout(
        (configured != null) && (configured.getTimeout() != null)
            ? configured.getTimeout()
            : DEFAULT_TIMEOUT);
    return result;

  }

  /**
   * Validates the values configured in this block.
   *
   * @param propertyPath The path of the resilience block (used in error messages)
   * @throws IllegalStateException If a value is invalid
   */
  public void validate(
      final String propertyPath) throws IllegalStateException {

    if ((maxRetries != null) && (maxRetries < 0)) {
      throw new IllegalStateException(
          "Property '%s.max-retries' must not be negative but is '%d'!"
              .formatted(propertyPath, maxRetries));
    }
    if ((initialInterval != null) && (initialInterval.isNegative() || initialInterval.isZero())) {
      throw new IllegalStateException(
          "Property '%s.initial-interval' must be positive but is '%s'!"
              .formatted(propertyPath, initialInterval));
    }
    if ((multiplier != null) && (multiplier < 1.0)) {
      throw new IllegalStateException(
          "Property '%s.multiplier' must be at least 1.0 but is '%s'!"
              .formatted(propertyPath, multiplier));
    }
    if ((timeout != null) && (timeout.isNegative() || timeout.isZero())) {
      throw new IllegalStateException(
          "Property '%s.timeout' must be positive but is '%s'!"
              .formatted(propertyPath, timeout));
    }

  }

}
