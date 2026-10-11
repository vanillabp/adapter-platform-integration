package io.vanillabp.integration.adapter.spi.health;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one BPMS adapter says about the BPMS it talks to. It is reported by
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#checkHealth()}.
 * The platform integration translates it into whatever that platform publishes (a Spring
 * Boot Actuator health indicator, a Quarkus readiness check).
 * <p>
 * An adapter which has nothing to say returns <code>null</code> instead of a health.
 * Nothing is honest. {@link Status#UP} would claim something nobody checked.
 * <p>
 * Two rules come from the way VanillaBP handles configuration, and they are part of this
 * contract:
 * <ul>
 * <li>An adapter which is not configured yet reports {@link Status#UNKNOWN}, never
 * {@link Status#DOWN}. The application started with a warning which guides the developer,
 * on purpose. A health endpoint must not turn that into an outage.</li>
 * <li>The {@link #details()} name the adapter id and the address it tried. So an operator
 * can act on the output of the endpoint without reading the configuration of the
 * application.</li>
 * </ul>
 *
 * @param adapterId The id of the adapter instance
 * @param adapterType The adapter's type (e.g. <code>camunda8</code>)
 * @param status What the adapter found
 * @param description One sentence for a human, e.g. what failed
 * @param details Named values an operator needs, e.g. the address and the version of
 *          the BPMS, in the order the adapter wrote them (see
 *          {@link DetailsBuilder#build()})
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
   * Builds the details map of an adapter health. It drops entries without a value, so the
   * endpoint never shows an empty line.
   */
  public static class DetailsBuilder {

    private final Map<String, String> details = new LinkedHashMap<>();

    /**
     * Starts an empty set of details. {@link AdapterHealth#detailsBuilder()} does the same.
     * It reads better where the health is built in one expression.
     */
    public DetailsBuilder() {
    }

    /**
     * Adds one detail, or drops it if the adapter has nothing to show for that name. So a
     * caller may pass a value it is not sure about, and does not have to check first.
     *
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

    /**
     * Closes the set, so nobody can change it afterwards. The details keep the order in
     * which the adapter added them. Somebody who reads the endpoint expects that order.
     *
     * @return The details which had a value, in the order they were added
     */
    public Map<String, String> build() {

      return Collections.unmodifiableMap(new LinkedHashMap<>(details));

    }

  }

  /**
   * Starts the details of a health.
   *
   * @return A builder for the {@link #details()} of an adapter health
   */
  public static DetailsBuilder detailsBuilder() {

    return new DetailsBuilder();

  }

  /**
   * The health of an adapter whose BPMS answered.
   *
   * @param adapterId The id of the adapter instance. With it an operator can tell two
   *          instances of one BPMS apart
   * @param adapterType The adapter's type (e.g. <code>camunda8</code>)
   * @param description One sentence for a human, e.g. which cluster answered
   * @param details Named values an operator needs, built with {@link #detailsBuilder()}
   * @return The health the platform integration publishes
   */
  public static AdapterHealth up(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.UP, description, details);

  }

  /**
   * The health of an adapter which could not reach its BPMS, or found it broken. It is the
   * only status which makes an application unhealthy. So the description says what failed,
   * and the details say which address was tried. An adapter reports this instead of
   * throwing. Otherwise the core would have to turn the exception into the same thing.
   *
   * @param adapterId The id of the adapter instance
   * @param adapterType The adapter's type (e.g. <code>camunda8</code>)
   * @param description One sentence for a human saying what failed
   * @param details Named values an operator needs, the address above all
   * @return The health the platform integration publishes
   */
  public static AdapterHealth down(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.DOWN, description, details);

  }

  /**
   * The health of an adapter which checked nothing, because it is not configured yet or
   * its check is switched off. It is never the answer for a BPMS which did not answer.
   * That is {@link #down}, and reporting it as unknown hides an outage.
   *
   * @param adapterId The id of the adapter instance
   * @param adapterType The adapter's type (e.g. <code>camunda8</code>)
   * @param description One sentence for a human saying why nothing was checked
   * @param details Named values an operator needs, e.g. the property which is missing
   * @return The health the platform integration publishes
   */
  public static AdapterHealth unknown(
      final String adapterId,
      final String adapterType,
      final String description,
      final Map<String, String> details) {

    return new AdapterHealth(adapterId, adapterType, Status.UNKNOWN, description, details);

  }

}
