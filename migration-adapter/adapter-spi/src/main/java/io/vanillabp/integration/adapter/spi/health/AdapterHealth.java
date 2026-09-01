package io.vanillabp.integration.adapter.spi.health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one BPMS adapter says about the BPMS it talks to, reported by
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#checkHealth()}
 * and translated by the platform integration into whatever that platform publishes
 * (a Spring Boot Actuator health indicator, a Quarkus readiness check).
 * <p>
 * An adapter which has nothing to contribute returns <code>null</code> instead of a
 * health: absent is honest, {@link Status#UP} would be a claim nobody checked.
 * <p>
 * Two rules follow from VanillaBP's configuration UX and are part of this contract:
 * <ul>
 * <li>an adapter which is not configured yet reports {@link Status#UNKNOWN}, never
 * {@link Status#DOWN} - the application booted with a guiding warning on purpose, and
 * a health endpoint must not turn that into an outage;</li>
 * <li>the {@link #details()} name the adapter id and the address it tried, so an
 * operator can act on the endpoint's output without reading the application's
 * configuration.</li>
 * </ul>
 *
 * @param adapterId The id of the adapter instance
 * @param adapterType The adapter's type (e.g. <code>camunda8</code>)
 * @param status What the adapter found
 * @param description One sentence for a human, e.g. what failed
 * @param details Named values an operator needs, e.g. the address and the version of
 *          the BPMS
 */
public record AdapterHealth(
                            String adapterId,
                            String adapterType,
                            Status status,
                            String description,
                            Map<String, String> details) {

  /**
   * The three answers an adapter may give.
   */
  public enum Status {

    /**
     * The BPMS answered.
     */
    UP,

    /**
     * The BPMS did not answer, or answered with a defect. This is the only status
     * which makes an application unhealthy.
     */
    DOWN,

    /**
     * Nothing was checked, and that is not a defect: the adapter is not configured
     * yet, or the check was switched off.
     */
    UNKNOWN
  }

  /**
   * Builds the details map of an adapter health, dropping entries without a value so
   * the endpoint never shows an empty line.
   */
  public static class DetailsBuilder {

    private final Map<String, String> details = new LinkedHashMap<>();

    /**
     * @param name The name of the detail
     * @param value The value, ignored if <code>null</code> or blank
     * @return This builder
     */
    public DetailsBuilder with(
        final String name,
        final String value) {

      if ((value != null) && !value.isBlank()) {
        details.put(name, value);
      }
      return this;

    }

    public Map<String, String> build() {

      return Map.copyOf(details);

    }

  }

  /**
   * @return A builder for the {@link #details()} of an adapter health
   */
  public static DetailsBuilder detailsBuilder() {

    return new DetailsBuilder();

  }

  public static AdapterHealth up(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.UP, description, details);

  }

  public static AdapterHealth down(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.DOWN, description, details);

  }

  public static AdapterHealth unknown(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.UNKNOWN, description, details);

  }

}
