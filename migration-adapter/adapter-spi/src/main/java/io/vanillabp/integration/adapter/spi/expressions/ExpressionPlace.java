package io.vanillabp.integration.adapter.spi.expressions;

/**
 * Where in a BPMN model an expression sits. An adapter names the place it read the
 * expression from. So the startup message can point at it without knowing anything about
 * the BPMS.
 * <p>
 * These are the places where a modeller reads data. They are not a list of everything a
 * BPMS evaluates. An expression which names a wired task, a delegate class or a form key
 * does not read data, so this check ignores it.
 * <p>
 * Sometimes a model has an expression in a place which nothing here describes. Then the
 * adapter reports {@link #SOMEWHERE_ELSE}, not the closest match. A wrong place sends the
 * reader to the wrong part of the model, and the element ID next to it is precise enough.
 */
public enum ExpressionPlace {

  /** The condition of a sequence flow, which is where most expressions of a model sit. */
  SEQUENCE_FLOW_CONDITION("the condition of a sequence flow"),

  /**
   * The condition of a conditional event. That is a catching intermediate event, a
   * boundary event or the start event of an event subprocess.
   */
  CONDITIONAL_EVENT_CONDITION("the condition of a conditional event"),

  /** The duration, the date or the cycle of a timer. */
  TIMER("a timer"),

  /** How often a multi-instance element runs. */
  MULTI_INSTANCE_CARDINALITY("the cardinality of a multi-instance element"),

  /** The collection a multi-instance element iterates. */
  MULTI_INSTANCE_COLLECTION("the collection of a multi-instance element"),

  /** When a multi-instance element stops, even though instances are left. */
  MULTI_INSTANCE_COMPLETION_CONDITION("the completion condition of a multi-instance element"),

  /** The loop condition of a standard loop. */
  LOOP_CONDITION("the condition of a loop"),

  /** The value by which a waiting message is correlated. */
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
   * The words the startup message uses for this place. They are written to fit after the
   * element they were read from.
   *
   * @return The wording
   */
  public String wording() {

    return wording;

  }

}
