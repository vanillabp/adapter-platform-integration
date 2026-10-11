package io.vanillabp.integration.adapter.spi.values;

/**
 * Which way a value travels between the application and the BPMS. Each way is judged by
 * different rules. So an adapter is always told which way it is asked about.
 */
public enum ValueDirection {

  /**
   * An attribute of the workflow aggregate on its way to the BPMS. Such a value helps
   * the model decide, so what counts is the expression language of the BPMS.
   */
  TO_BPMS,

  /**
   * A value on its way back into a <code>&#64;TaskParam</code> parameter. Nothing in
   * the model reads it. What counts is whether the serialization of the BPMS returns
   * the value as the type the parameter declares.
   */
  FROM_BPMS

}
