package io.vanillabp.migration.test.expressions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.expressions.ExpressionForm;
import io.vanillabp.integration.adapter.migration.expressions.ModelExpressionCheck;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings.Severity;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.migration.test.startup.WhatWasFound;

/**
 * What an expression of a BPMN model costs, and which expressions are worth saying it
 * about. The rule is the platform's, so this is where it is pinned down: an expression
 * naming one variable is what VanillaBP recommends and is reported nowhere, one walking a
 * path or calling something is a WARN, and one merely computing is a NOTICE.
 * <p>
 * The whole way through a booted application is held by
 * {@code ExpressionsInTheModelTest} of each platform, which is where the reporting adapter
 * and the configuration meet the check.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ModelExpressionCheckTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "ShippingProcess";

  private StartupFindings findings;

  /**
   * The check with a configuration which accepts nothing, which is where an application
   * starts.
   *
   * @return The check under test
   */
  private ModelExpressionCheck check() {

    return check(MigrationAdapterProperties.builder().build());

  }

  private ModelExpressionCheck check(
      final MigrationAdapterProperties properties) {

    findings = new StartupFindings();
    return new ModelExpressionCheck(findings, properties);

  }

  private static ModelExpression expression(
      final String elementId,
      final String expression) {

    // as an adapter reports it which did not strip its delimiters, the case the rule has
    // to survive
    return ModelExpression.of(elementId, ExpressionPlace.SEQUENCE_FLOW_CONDITION, expression);

  }

  private List<String> warnings() {

    return WhatWasFound.messages(findings, Severity.WARNING);

  }

  private List<String> notices() {

    return WhatWasFound.messages(findings, Severity.NOTICE);

  }

  @Test
  @DisplayName("An expression naming one variable is reported nowhere")
  public void anExpressionNamingOneVariableIsNotReported() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Normal", "${shippedAsNormalItem}"),
                    expression("Flow_Big", "${shippedAsBigItem}"),
                    expression("Activity_Items", "${itemIds}")));

    assertTrue(WhatWasFound.messages(findings).isEmpty(), findings.findings().toString());

  }

  @Test
  @DisplayName("A path warns, names the element, the place and the way out, and counts what is fine")
  public void aPathIsWarnedAbout() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Express", "${order.shipping.express}"),
                    expression("Flow_Normal", "${shippedAsNormalItem}")));

    assertEquals(1, warnings().size(), warnings().toString());
    final var warning = warnings().getFirst();
    assertTrue(warning.contains("'${order.shipping.express}'"), warning);
    assertTrue(warning.contains("'Flow_Express'"), warning);
    assertTrue(warning.contains("the condition of a sequence flow"), warning);
    assertTrue(warning.contains("isShippedAsNormalItem()"), warning);
    assertTrue(warning.contains("1 of the 2 expressions"), warning);
    assertTrue(
        warning
            .contains(
                "vanillabp.workflow-modules.test-module.workflows.ShippingProcess.accept-expressions-in-the-model"),
        warning);
    // the expression which is fine is counted, never listed: the message is about what
    // to change
    assertFalse(warning.contains("'Flow_Normal'"), warning);
    assertTrue(notices().isEmpty(), notices().toString());

  }

  @Test
  @DisplayName("A call warns as well, and the two findings are one warning per process")
  public void aCallIsWarnedAboutTogetherWithThePath() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Express", "${order.shipping.express}"),
                    expression("Flow_Many", "${count(order.items) > 3}")));

    assertEquals(1, warnings().size(), warnings().toString());
    final var warning = warnings().getFirst();
    assertTrue(warning.contains("'${order.shipping.express}'"), warning);
    assertTrue(warning.contains("'${count(order.items) > 3}'"), warning);
    assertTrue(warning.contains("0 of the 2 expressions"), warning);

  }

  @Test
  @DisplayName("An expression which only computes is a notice, not a warning")
  public void aComputingExpressionIsANotice() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Small", "${not bigItem}"),
                    expression("Flow_Big", "${shippedAsBigItem}")));

    assertTrue(warnings().isEmpty(), warnings().toString());
    assertEquals(1, notices().size(), notices().toString());
    final var notice = notices().getFirst();
    assertTrue(notice.contains("'${not bigItem}'"), notice);
    assertTrue(notice.contains("compute instead of naming a variable"), notice);
    assertTrue(notice.contains("1 of the 2 expressions"), notice);

  }

  @Test
  @DisplayName("Both halves of the finding are said, each at its own level")
  public void bothHalvesAreSaidSeparately() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Express", "${order.shipping.express}"),
                    expression("Flow_Small", "${not bigItem}")));

    assertEquals(1, warnings().size(), warnings().toString());
    assertEquals(1, notices().size(), notices().toString());
    assertTrue(warnings().getFirst().contains("'${order.shipping.express}'"), warnings().toString());
    assertFalse(warnings().getFirst().contains("'${not bigItem}'"), warnings().toString());
    assertTrue(notices().getFirst().contains("'${not bigItem}'"), notices().toString());

  }

  @Test
  @DisplayName("The place the expression sits in is named as the adapter reported it")
  public void thePlaceIsNamed() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    new ModelExpression(
                        "Activity_Items", ExpressionPlace.MULTI_INSTANCE_COLLECTION, "${order.items}", "order.items")));

    assertTrue(
        warnings().getFirst().contains("the collection of a multi-instance element"),
        warnings().toString());

  }

  @Test
  @DisplayName("A workflow which accepts its expressions is silent")
  public void anAcceptingWorkflowIsSilent() {

    check(acceptedAtTheWorkflow())
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List.of(expression("Flow_Express", "${order.shipping.express}")));

    assertTrue(WhatWasFound.messages(findings).isEmpty(), findings.findings().toString());

  }

  @Test
  @DisplayName("The acceptance is read at the workflow module and at the application as well")
  public void theAcceptanceIsReadAtEveryLevel() {

    for (final var properties : List.of(acceptedAtTheWorkflowModule(), acceptedAtTheApplication())) {
      check(properties)
          .reportModelExpressions(
              MODULE,
              PROCESS,
              List.of(expression("Flow_Express", "${order.shipping.express}")));
      assertTrue(WhatWasFound.messages(findings).isEmpty(), findings.findings().toString());
    }

  }

  @Test
  @DisplayName("What the workflow says beats what the levels above it say")
  public void theWorkflowBeatsTheLevelsAboveIt() {

    final var properties = MigrationAdapterProperties
        .builder()
        .acceptExpressionsInTheModel(Boolean.TRUE)
        .workflowModules(
            java.util.Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .acceptExpressionsInTheModel(Boolean.TRUE)
                        .workflows(
                            java.util.Map
                                .of(
                                    PROCESS,
                                    WorkflowAdapterProperties
                                        .builder()
                                        .acceptExpressionsInTheModel(Boolean.FALSE)
                                        .build()))
                        .build()))
        .build();

    check(properties)
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List.of(expression("Flow_Express", "${order.shipping.express}")));

    assertEquals(1, warnings().size(), warnings().toString());

  }

  @Test
  @DisplayName("Nothing reported, nothing said - and a check without a configuration still works")
  public void nothingIsSaidWhereNothingWasReported() {

    final var check = check(null);
    check.reportModelExpressions(MODULE, PROCESS, null);
    check.reportModelExpressions(MODULE, PROCESS, List.of());
    check.reportModelExpressions(MODULE, PROCESS, java.util.Arrays.asList((ModelExpression) null));
    check
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List.of(ModelExpression.of("Flow_Empty", ExpressionPlace.SEQUENCE_FLOW_CONDITION, "   ")));

    assertTrue(WhatWasFound.messages(findings).isEmpty(), findings.findings().toString());

  }

  @Test
  @DisplayName("The same expression reported twice is listed once")
  public void aRepeatedExpressionIsListedOnce() {

    check()
        .reportModelExpressions(
            MODULE,
            PROCESS,
            List
                .of(
                    expression("Flow_Express", "${order.shipping.express}"),
                    expression("Flow_Express", "${order.shipping.express}")));

    final var warning = warnings().getFirst();
    assertEquals(
        1,
        warning.split("\\$\\{order.shipping.express\\}", -1).length - 1,
        warning);
    assertTrue(warning.contains("0 of the 1 expressions"), warning);

  }

  @Test
  @DisplayName("The forms an expression can have, read off its text")
  public void theFormsAreReadOffTheText() {

    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("shippedAsNormalItem"));
    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("  loopCounter  "));
    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("_private$Name2"));
    assertEquals(ExpressionForm.WALKS_A_PATH, ExpressionForm.of("order.shipping.express"));
    assertEquals(ExpressionForm.WALKS_A_PATH, ExpressionForm.of("orderItems[1].shippedAsNormalItem"));
    assertEquals(ExpressionForm.WALKS_A_PATH, ExpressionForm.of("order . express"));
    assertEquals(ExpressionForm.CALLS_SOMETHING, ExpressionForm.of("order.getShipping().isExpress()"));
    assertEquals(ExpressionForm.CALLS_SOMETHING, ExpressionForm.of("count(items) > 0"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of("not bigItem"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of("amount > 1000"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of("'PT1H'"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of("express shipping"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of(null));
    // delimiters an adapter did not strip are taken off, one block of them
    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("${shippedAsNormalItem}"));
    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("#{shippedAsNormalItem}"));
    assertEquals(ExpressionForm.NAMES_A_VARIABLE, ExpressionForm.of("= shippedAsNormalItem"));
    assertEquals(ExpressionForm.WALKS_A_PATH, ExpressionForm.of("${order.express}"));
    assertEquals(ExpressionForm.COMPUTES, ExpressionForm.of("${a}-${b}"));
    assertTrue(ExpressionForm.WALKS_A_PATH.reachesIntoTheData());
    assertTrue(ExpressionForm.CALLS_SOMETHING.reachesIntoTheData());
    assertFalse(ExpressionForm.COMPUTES.reachesIntoTheData());
    assertFalse(ExpressionForm.NAMES_A_VARIABLE.reachesIntoTheData());

  }

  @Test
  @DisplayName("An adapter which reports no place and no body gets both from what it did report")
  public void whatAnAdapterLeavesOutIsFilledIn() {

    final var reported = ModelExpression.of(" Flow_Express ", null, " order.express ");

    assertEquals("Flow_Express", reported.elementId());
    assertEquals(ExpressionPlace.SOMEWHERE_ELSE, reported.place());
    assertEquals("order.express", reported.expression());
    assertEquals("order.express", reported.body());
    assertEquals("the model", reported.place().wording());

  }

  private static MigrationAdapterProperties acceptedAtTheWorkflow() {

    return MigrationAdapterProperties
        .builder()
        .workflowModules(
            java.util.Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .workflows(
                            java.util.Map
                                .of(
                                    PROCESS,
                                    WorkflowAdapterProperties
                                        .builder()
                                        .acceptExpressionsInTheModel(Boolean.TRUE)
                                        .build()))
                        .build()))
        .build();

  }

  private static MigrationAdapterProperties acceptedAtTheWorkflowModule() {

    return MigrationAdapterProperties
        .builder()
        .workflowModules(
            java.util.Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .acceptExpressionsInTheModel(Boolean.TRUE)
                        .build()))
        .build();

  }

  private static MigrationAdapterProperties acceptedAtTheApplication() {

    return MigrationAdapterProperties
        .builder()
        .acceptExpressionsInTheModel(Boolean.TRUE)
        .build();

  }

}
