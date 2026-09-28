package io.vanillabp.integration.adapter.spi.expressions;

/**
 * Where in a BPMN model an expression sits. An adapter names the place it read the
 * expression from, so the startup message can point at it without knowing anything about
 * the BPMS.
 * <p>
 * The places are the ones a modeller puts data reads into. They are not a list of
 * everything a BPMS evaluates: an expression naming a wired task, a delegate class or a
 * form key is not a data read and is none of this check's business.
 * <p>
 * An adapter whose model carries an expression in a place nothing here describes reports
 * {@link #SOMEWHERE_ELSE} rather than the closest match. A wrong place sends the reader to
 * the wrong part of the model, and the element ID beside it is precise enough on its own.
 */
public enum ExpressionPlace {

  /** The condition of a sequence flow, which is where most expressions of a model sit. */
  SEQUENCE_FLOW_CONDITION("the condition of a sequence flow"),

  /**
   * The condition of a conditional event - a catching intermediate event, a boundary
   * event or the start event of an event subprocess.
   */
  CONDITIONAL_EVENT_CONDITION("the condition of a conditional event"),

  /** The duration, the date or the cycle of a timer. */
  TIMER("a timer"),

  /** How often a multi-instance element runs. */
  MULTI_INSTANCE_CARDINALITY("the cardinality of a multi-instance element"),

  /** The collection a multi-instance element iterates. */
  MULTI_INSTANCE_COLLECTION("the collection of a multi-instance element"),

  /** When a multi-instance element stops although instances are left. */
  MULTI_INSTANCE_COMPLETION_CONDITION("the completion condition of a multi-instance element"),

  /** The loop condition of a standard loop. */
  LOOP_CONDITION("the condition of a loop"),

  /** The value a message waits to be correlated by. */
  MESSAGE_CORRELATION_KEY("the correlation key of a message"),

  /** An input of a decision the model calls, which a DMN table reads. */
  DECISION_INPUT("an input of a decision"),

  /** A value a model maps into or out of an element. */
  INPUT_OR_OUTPUT_MAPPING("an input or output mapping"),

  /** A place none of the others describes. */
  SOMEWHERE_ELSE("the model");

  private final String wording;

  ExpressionPlace(
      final String wording) {

    this.wording = wording;

  }

  /**
   * The words the startup message uses for this place, written so they fit behind the
   * element they were read from.
   *
   * @return The wording
   */
  public String wording() {

    return wording;

  }

}
