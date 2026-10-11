package io.vanillabp.integration.adapter.spi.expressions;

/**
 * One expression which a BPMS adapter read out of a BPMN model. It is reported to
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportModelExpressions(String, String, java.util.Collection)}
 * while the model is wired.
 * <p>
 * The expression language decides what the adapter does and what the core does. The
 * adapter finds the expressions of a model and strips their delimiters, because the
 * language and the places belong to the BPMS. The core decides what an expression costs
 * the application, because the answer has to be the same on every BPMS the model is
 * deployed to.
 * <p>
 * Only the expressions which read the DATA of the workflow are reported. An expression
 * which names a wired task, a delegate class, a form key or anything else the BPMS
 * resolves itself is not reported. An adapter which cannot read its models reports
 * nothing at all. Nothing is guessed from a missing report.
 *
 * @param elementId The BPMN ID of the element the expression was read from. A modeller
 *          searches the model for this ID
 * @param place Where in that element it sits
 * @param expression The expression as the model carries it, with its delimiters
 *          (<code>${order.express}</code>). So the developer reads back what they wrote
 * @param body What the expression language evaluates, without the delimiters
 *          (<code>order.express</code>). This is the part the core judges. If it is empty,
 *          the expression itself is used, which is right for a language without delimiters
 */
public record ModelExpression(
                              String elementId,
                              ExpressionPlace place,
                              String expression,
                              String body) {

  /**
   * Cleans up what an adapter reports. Whitespace around an expression belongs to the XML
   * it was read from, not to the expression. A missing place becomes
   * {@link ExpressionPlace#SOMEWHERE_ELSE}. A missing body becomes the expression itself.
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
