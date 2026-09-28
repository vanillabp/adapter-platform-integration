package io.vanillabp.integration.adapter.migration.expressions;

import java.util.regex.Pattern;

/**
 * What an expression of a BPMN model does, as far as its text says - the rule behind
 * {@link ModelExpressionCheck} and the reason that rule lives in the platform: every
 * adapter finds the expressions of its own models, and all of them are answered the same
 * way here.
 * <p>
 * The forms are told apart by shape, not by a grammar. Both expression languages VanillaBP
 * meets write a variable read as a bare name, a member access with a dot and a function call
 * with a parenthesis, and that is all this rule needs. Everything else it cannot place is
 * {@link #COMPUTES}, which is the mildest of the findings - a form nobody foresaw must not
 * turn into the loudest message.
 * <p>
 * A name with a space in it is legal in FEEL and is still not read as a name here: the
 * variables VanillaBP hands a model are named after the accessors of a workflow aggregate,
 * so a name which needs a space is not one VanillaBP put there.
 */
public enum ExpressionForm {

  /**
   * The expression is the name of one variable, which is what VanillaBP recommends: the
   * model asks a question and the workflow aggregate answers it under that name. Nothing
   * is reported about it.
   */
  NAMES_A_VARIABLE,

  /**
   * The expression walks into the object behind a name (<code>order.shipping.express</code>),
   * index included. It binds the model to the shape of the application's data.
   */
  WALKS_A_PATH,

  /**
   * The expression calls something - a method of an object, a function of the BPMS.
   * It binds the model to the shape of the data or to the function library of one BPMS,
   * usually to both.
   */
  CALLS_SOMETHING,

  /**
   * Anything else: a negation, a comparison, a literal, several names joined by an
   * operator. It leaves the application's data model alone and binds the expression
   * language, whose words for "not" differ from one BPMS to the next.
   */
  COMPUTES;

  /**
   * The name of one variable and nothing else.
   */
  private static final Pattern A_NAME = Pattern.compile("[A-Za-z_$][\\w$]*");

  /**
   * A name followed by member accesses and indexes, and nothing else. Whitespace around
   * the dots is a modeller's formatting rather than a second thing the expression does.
   */
  private static final Pattern A_PATH = Pattern
      .compile("[A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*|\\s*\\[\\s*\\d+\\s*\\])+");

  /**
   * Reads the form off the text an expression language evaluates.
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
      // a parenthesis is a call in both languages, and a call is judged as one however
      // plain the rest of the expression looks
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
   * Takes the delimiters of an expression language off a text which still carries them -
   * one <code>${...}</code> or <code>#{...}</code> around the whole of it, or the
   * <code>=</code> a FEEL expression may begin with.
   * <p>
   * Stripping them is the adapter's job, since they belong to its BPMS, and this is the net
   * under it: an adapter reporting an expression as it stands in the model would otherwise
   * have every one of them read as a computation, which is the wrong half of the finding.
   * Two blocks in one attribute (<code>${a}-${b}</code>) are left alone - that text really
   * is more than one expression.
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
   * Whether an expression of this form binds the application's data model or the function
   * library of one BPMS - the findings worth a warning, as against the ones which only bind
   * the expression language.
   *
   * @return Whether it reaches into the application's data
   */
  public boolean reachesIntoTheData() {

    return (this == WALKS_A_PATH) || (this == CALLS_SOMETHING);

  }

}
