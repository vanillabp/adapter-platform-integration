package io.vanillabp.integration.adapter.spi.values;

/**
 * What one BPMS does with one Java type, in one direction. The core asks every adapter
 * of a workflow while the application starts. It puts the answers into its message about
 * a value which is not portable.
 * <p>
 * The third answer is the important one. If an adapter cannot say what its BPMS does, it
 * says so, and that never stops a startup. For example, Camunda 8 drops the scale of a
 * decimal inside the broker, and only a round trip through a running cluster shows it.
 * An application whose BPMS cannot be reached at startup still has to start.
 *
 * @param kind What the BPMS does with the type
 * @param explanation One sentence for the startup message, in the adapter's own words.
 *          It says what happens to the value. <code>null</code> for {@link Kind#SURVIVES},
 *          which needs no words
 */
public record ValueTypeVerdict(
                               Kind kind,
                               String explanation) {

  /**
   * What a BPMS does with a type.
   */
  public enum Kind {

    /**
     * The value arrives as the type it was given, and comes back as that type.
     */
    SURVIVES,

    /**
     * The value arrives as something else: another type, or the same type with
     * something lost. The explanation says what.
     */
    CHANGED,

    /**
     * The adapter cannot say. This answer never stops a startup.
     */
    CANNOT_SAY

  }

  /**
   * The answer of an adapter whose BPMS returns the value as it got it.
   *
   * @return The BPMS holds the type as it is
   */
  public static ValueTypeVerdict survives() {

    return new ValueTypeVerdict(Kind.SURVIVES, null);

  }

  /**
   * The answer of an adapter whose BPMS changes the value on the way.
   *
   * @param whatHappens What the BPMS does with the value, as one sentence a startup
   *          message can quote
   * @return The BPMS changes the value
   */
  public static ValueTypeVerdict changed(
      final String whatHappens) {

    return new ValueTypeVerdict(Kind.CHANGED, whatHappens);

  }

  /**
   * The answer of an adapter which cannot tell what its BPMS does with the type.
   *
   * @param why Why the adapter cannot answer, as one sentence a startup message can
   *          quote
   * @return The adapter does not know
   */
  public static ValueTypeVerdict cannotSay(
      final String why) {

    return new ValueTypeVerdict(Kind.CANNOT_SAY, why);

  }

}
