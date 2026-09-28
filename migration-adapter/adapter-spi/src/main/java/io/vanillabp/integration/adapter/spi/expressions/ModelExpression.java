package io.vanillabp.integration.adapter.spi.expressions;

/**
 * One expression a BPMS adapter read out of a BPMN model, reported to
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportModelExpressions(String, String, java.util.Collection)}
 * while the model is wired.
 * <p>
 * The split between an adapter and the core runs along the expression language. Finding
 * the expressions of a model and stripping whatever delimits them is the adapter's work,
 * because the language and the places belong to the BPMS. Deciding what an expression
 * costs the application is the core's, because the answer has to be the same whichever
 * BPMS the model is deployed to.
 * <p>
 * Reported are the expressions which read the workflow's DATA. An expression naming a
 * wired task, a delegate class, a form key or anything else the BPMS resolves for itself is
 * not one of them, and an adapter which cannot read its models reports nothing at all -
 * nothing is guessed from the absence.
 *
 * @param elementId The BPMN ID of the element the expression was read from, which is what
 *          a modeller searches their model for
 * @param place Where in that element it sits
 * @param expression The expression as the model carries it, delimiters included
 *          (<code>${order.express}</code>), so the developer reads back what they wrote
 * @param body What the expression language evaluates, the delimiters stripped
 *          (<code>order.express</code>) - the part the core judges. Left empty it is taken
 *          from the expression itself, which is right for a language having no delimiters
 */
public record ModelExpression(
                              String elementId,
                              ExpressionPlace place,
                              String expression,
                              String body) {

  /**
   * Normalizes what an adapter reports: whitespace around an expression belongs to the XML
   * it was read from rather than to the expression, an unnamed place is
   * {@link ExpressionPlace#SOMEWHERE_ELSE}, and a body nobody stripped is the expression
   * itself.
   */
  public ModelExpression {

    elementId = elementId == null
        ? null
        : elementId.trim();
    expression = expression == null
        ? null
        : expression.trim();
    body = (body == null) || body.isBlank()
        ? expression
        : body.trim();
    place = place == null
        ? ExpressionPlace.SOMEWHERE_ELSE
        : place;

  }

  /**
   * The expression of an element whose language needs no delimiters, or whose delimiters
   * the adapter did not strip.
   *
   * @param elementId The BPMN ID of the element
   * @param place Where in that element the expression sits
   * @param expression The expression as the model carries it
   * @return The reportable expression
   */
  public static ModelExpression of(
      final String elementId,
      final ExpressionPlace place,
      final String expression) {

    return new ModelExpression(elementId, place, expression, null);

  }

}
