package io.vanillabp.integration.adapter.migration.expressions;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.spi.startup.StartupTopic;

/**
 * The startup hint about an expression in a BPMN model which is more than the name of a
 * variable.
 * <p>
 * Such an expression binds the model to two things at once: to the shape of the objects it
 * walks, and to the expression language of the BPMS it is deployed to. An attribute renamed
 * in the application then breaks a model nobody touched, and a move to another BPMS breaks
 * the expression. What VanillaBP recommends instead is a getter on the workflow aggregate
 * which answers the question the model asks, so the model reads one name and survives both
 * changes. The wiki has taught that technique for years; this check is what makes it
 * noticeable when a model does it differently.
 * <p>
 * Reading the model is the adapter's job, since only it knows its BPMN dialect and its
 * expression language, and judging what an expression costs is the core's, since the answer
 * must not depend on the BPMS: the adapter reports the expressions it found
 * ({@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportModelExpressions},
 * once per BPMN process), and the rule which sorts them is {@link ExpressionForm}.
 * <p>
 * The findings are a WARN and a NOTICE, never a refusal: an existing model would stop
 * deploying over a style we recommend, and a check which reads expressions can misread one.
 * The two levels are the two halves of the binding. An expression reaching into the
 * application's data is the warning, because a rename in Java breaks the model silently.
 * An expression which only computes is the notice, because all it binds is the language.
 * A process whose expressions are meant as they are says so once, with the property the
 * message hands out.
 * <p>
 * Why the rule lives here while the detection lives in every adapter, and why an expression
 * naming a single variable is reported nowhere, is written down in
 * {@code DECISIONS.pending/502.md} of this repository, until the decision is numbered.
 */
public class ModelExpressionCheck {

  /**
   * Where the hints go.
   */
  private final StartupFindings findings;

  /**
   * What the application says about the processes whose expressions are meant as they are.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Built by the registry which wires the workflow tasks, once per application.
   *
   * @param findings Where the hints are reported
   * @param properties The VanillaBP configuration, which may accept the expressions of a
   *          process. A registry built without a configuration, in a test, passes
   *          <code>null</code>, and nothing is accepted then
   */
  public ModelExpressionCheck(
      final StartupFindings findings,
      final MigrationAdapterProperties properties) {

    this.findings = findings;
    this.properties = properties;

  }

  /**
   * Sorts the expressions an adapter read out of one BPMN process and says what the ones
   * which are more than a name cost.
   * <p>
   * Called once per BPMN process, with every expression the model carries - the harmless
   * ones included, because the message counts them: a developer reading that five of seven
   * expressions already name a variable learns how far their model is, and a process doing
   * everything right stays silent.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param expressions The expressions of that process, as its adapter read them
   */
  public void reportModelExpressions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Collection<ModelExpression> expressions) {

    if ((expressions == null) || expressions.isEmpty()) {
      return;
    }
    if ((properties != null) && properties.acceptsExpressionsInTheModel(workflowModuleId, bpmnProcessId)) {
      return;
    }
    final var readable = expressions
        .stream()
        .filter(expression -> expression != null)
        .filter(expression -> (expression.body() != null) && !expression.body().isBlank())
        .distinct()
        .toList();
    if (readable.isEmpty()) {
      return;
    }
    final var namingAVariable = readable
        .stream()
        .filter(expression -> ExpressionForm.of(expression.body()) == ExpressionForm.NAMES_A_VARIABLE)
        .count();
    final var howManyAreFine = "%d of the %d expressions of this process name a variable and nothing else."
        .formatted(namingAVariable, readable.size());
    final var scope = "process '%s' of workflow module '%s'"
        .formatted(bpmnProcessId, workflowModuleId);
    final var howToAcceptThem = """
        Where the expressions of this process are meant as they are, say so once and this \
        message is gone:

          %s: true"""
        .formatted(
            MigrationAdapterProperties
                .acceptExpressionsInTheModelProperty(workflowModuleId, bpmnProcessId));

    warnAboutWhatReachesIntoTheData(
        scope,
        listed(readable, ExpressionForm::reachesIntoTheData),
        howManyAreFine,
        howToAcceptThem);
    noticeWhatOnlyComputes(
        scope,
        listed(readable, form -> !form.reachesIntoTheData()),
        howManyAreFine,
        howToAcceptThem);

  }

  /**
   * The warning about the expressions which walk a path or call something: those are the
   * ones a rename in the application breaks, and the modeller who wrote them never sees
   * that rename.
   *
   * @param scope What the finding is about, for the box
   * @param expressions The expressions as the message lists them, empty where there are
   *          none
   * @param howManyAreFine How many of the process' expressions name a variable
   * @param howToAcceptThem The property which accepts the expressions of this process
   */
  private void warnAboutWhatReachesIntoTheData(
      final String scope,
      final String expressions,
      final String howManyAreFine,
      final String howToAcceptThem) {

    if (expressions.isEmpty()) {
      return;
    }
    findings
        .warn(
            StartupTopic.BPMN_MODELS,
            scope,
            """
                Expressions of a BPMN process read the application's data by more than the name \
                of one variable:
                %s
                An expression like this binds the model twice: to the shape of the objects it \
                walks, and to the expression language of this BPMS. An attribute renamed in Java \
                then breaks a model nobody touched, and a move to another BPMS breaks the \
                expression.

                What VanillaBP recommends is a getter which answers the question the model asks - \
                'public boolean isShippedAsNormalItem()' on the workflow aggregate - so the model \
                reads '${shippedAsNormalItem}' and survives both changes. The wiki page 'Workflow \
                aggregates' walks through it. %s A path into one item of a multi-instance \
                subprocess is the one exception that page names.

                %s"""
                .formatted(expressions, howManyAreFine, howToAcceptThem));

  }

  /**
   * The notice about the expressions which compute: they leave the data model alone, so
   * they are the smaller half of the finding, and they are said at the smaller level.
   *
   * @param scope What the finding is about, for the box
   * @param expressions The expressions as the message lists them, empty where there are
   *          none
   * @param howManyAreFine How many of the process' expressions name a variable
   * @param howToAcceptThem The property which accepts the expressions of this process
   */
  private void noticeWhatOnlyComputes(
      final String scope,
      final String expressions,
      final String howManyAreFine,
      final String howToAcceptThem) {

    if (expressions.isEmpty()) {
      return;
    }
    findings
        .notice(
            StartupTopic.BPMN_MODELS,
            scope,
            """
                Expressions of a BPMN process compute instead of naming a variable:
                %s
                They leave the application's data model alone, which makes this the smaller half \
                of the story: what they bind is the expression language of this BPMS, whose words \
                for 'not' and whose way of comparing differ from the next one's. A getter on the \
                workflow aggregate moves the computation into Java, where a test reaches it, and \
                leaves one name in the model. %s

                %s"""
                .formatted(expressions, howManyAreFine, howToAcceptThem));

  }

  /**
   * The expressions as a message lists them - one per line, with the element they sit in
   * and the place inside it, in the order the adapter reported them.
   *
   * @param expressions The readable expressions of the process
   * @param half Which half of the finding to list, asked of the form of each expression
   * @return The lines, empty where no expression belongs to that half
   */
  private static String listed(
      final List<ModelExpression> expressions,
      final java.util.function.Predicate<ExpressionForm> half) {

    return expressions
        .stream()
        .filter(expression -> {
          final var form = ExpressionForm.of(expression.body());
          return (form != ExpressionForm.NAMES_A_VARIABLE) && half.test(form);
        })
        .map(expression -> "  - '%s' at '%s' (%s)"
            .formatted(
                expression.expression(),
                expression.elementId(),
                expression.place().wording()))
        .collect(Collectors.joining("\n"));

  }

}
