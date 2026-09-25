package io.vanillabp.integration.adapter.spi.values;

/**
 * Which way a value travels between the application and the BPMS. The two ways are
 * judged by different things, which is why an adapter is always told which one it is
 * asked about.
 */
public enum ValueDirection {

  /**
   * An attribute of the workflow aggregate on its way to the BPMS. Such a value is
   * there to carry a decision, so what counts is the expression language of the BPMS.
   */
  TO_BPMS,

  /**
   * A value on its way back into a <code>&#64;TaskParam</code> parameter. Nothing in
   * the model reads it, so what counts is whether the serialization of the BPMS hands
   * the value back as the type the parameter declares.
   */
  FROM_BPMS

}
