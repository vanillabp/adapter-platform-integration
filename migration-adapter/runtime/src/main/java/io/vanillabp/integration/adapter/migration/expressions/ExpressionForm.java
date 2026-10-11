package io.vanillabp.integration.adapter.migration.expressions;

import java.util.regex.Pattern;

/**
 * What an expression of a BPMN model does, as far as its text tells. This is the rule
 * behind {@link ModelExpressionCheck}. The rule lives in the platform because every adapter
 * finds the expressions of its own models, and here all of them get the same answer.
 * <p>
 * The forms are told apart by their shape, not by a grammar. VanillaBP meets two expression
 * languages. Both write a variable as a bare name, a member access with a dot and a function
 * call with a parenthesis. That is all this rule needs. Everything else counts as
 * {@link #COMPUTES}, the mildest of the findings. A form nobody foresaw must not cause the
 * loudest message.
 * <p>
 * FEEL allows a space in a name, but this rule does not read such a text as a name. The
 * variables which VanillaBP hands to a model are named after the getters of a workflow
 * aggregate. So a name with a space is not one that VanillaBP put there.
 */
public enum ExpressionForm {

  /**
   * The expression is the name of one variable. VanillaBP recommends this form. The model
   * asks a question, and the workflow aggregate answers it under that name. Nothing is
   * reported about it.
   */
  NAMES_A_VARIABLE,

  /**
   * The expression walks into the object behind a name (<code>order.shipping.express</code>),
   * maybe with an index. It ties the model to the shape of the application's data.
   */
  WALKS_A_PATH,

  /**
   * The expression calls something, for example a method of an object or a function of the
   * BPMS. It ties the model to the shape of the data or to the functions of one BPMS. Usually
   * it ties it to both.
   */
  CALLS_SOMETHING,

  /**
   * Anything else, for example a negation, a comparison, a literal, or several names joined by
   * an operator. It leaves the application's data model alone. But it ties the model to the
   * expression language, and each BPMS has its own word for "not".
   */
  COMPUTES;

  /**
   * The name of one variable and nothing else.
   */
  private static final Pattern A_NAME = Pattern.compile("[A-Za-z_$][\\w$]*");

  /**
   * A name followed by member accesses and indexes, and nothing else. Whitespace around the
   * dots is only how the modeller formatted it. It does not make the expression do more.
   */
  private static final Pattern A_PATH = Pattern
      .compile("[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*|\\s*\\[\\s*\\d+\\s*\\])+");

  /**
   * Reads the form from the text which an expression language evaluates.
   *
   * @param body The expression without the delimiters of its language
   * @return The form, and {@link #COMPUTES} for a text this rule cannot place
   */
  public static ExpressionForm of(
      final String body) {

    if (body == null) {
      return COMPUTES;
    }
    final var text = unwrapped(body.trim());
    if (text.indexOf('(') >= 0) {
      // a parenthesis is a call in both languages. It counts as a call, however plain the
      // rest of the expression looks
      return CALLS_SOMETHING;
    }
    if (A_NAME.matcher(text).matches()) {
      return NAMES_A_VARIABLE;
    }
    if (A_PATH.matcher(text).matches()) {
      return WALKS_A_PATH;
    }
    return COMPUTES;

  }

  /**
   * Removes the delimiters of an expression language from a text which still has them. That
   * is one <code>${...}</code> or <code>#{...}</code> around the whole text, or the
   * <code>=</code> at the start of a FEEL expression.
   * <p>
   * Removing them is the adapter's job, because they belong to its BPMS. This is the safety
   * net. An adapter might report an expression exactly as it stands in the model. Without
   * this net every such expression would count as a computation, which is the wrong half of
   * the finding. Two blocks in one attribute (<code>${a}-${b}</code>) stay as they are. That
   * text really is more than one expression.
   *
   * @param text The trimmed expression
   * @return What the language evaluates
   */
  private static String unwrapped(
      final String text) {

    if (text.startsWith("=")) {
      return text.substring(1).trim();
    }
    final var wrapped = (text.startsWith("${") || text.startsWith("#{")) && text
        .endsWith("}") && (text.indexOf('}') == (text.length() - 1));
    return wrapped
        ? text.substring(2, text.length() - 1).trim()
        : text;

  }

  /**
   * Whether an expression of this form ties the model to the application's data model or to
   * the functions of one BPMS. Those findings are worth a warning. The others only tie it to
   * the expression language.
   *
   * @return Whether it reaches into the application's data
   */
  public boolean reachesIntoTheData() {

    return (this == WALKS_A_PATH) || (this == CALLS_SOMETHING);

  }

}
