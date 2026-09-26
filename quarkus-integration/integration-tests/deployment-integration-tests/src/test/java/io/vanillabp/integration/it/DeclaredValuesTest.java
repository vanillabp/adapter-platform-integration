package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.it.UndeclaredValuesTest.LoanAggregate;
import io.vanillabp.integration.it.UndeclaredValuesTest.LoanAggregatePersistence;
import io.vanillabp.integration.it.UndeclaredValuesTest.LoanApprovalWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The same application as in {@code UndeclaredValuesTest}, with the decimal declared at
 * its workflow and the parameter declared at its task. It starts, which is what a
 * declaration is for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeclaredValuesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("portable-values-declared/application.yaml", "application.yaml")
          .addClass(LoanAggregate.class)
          .addClass(LoanAggregatePersistence.class)
          .addClass(LoanApprovalWorkflowService.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"),
              "processes/dummy/LoanApprovalProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  LoanApprovalWorkflowService workflowService;

  @Test
  @DisplayName("The declarations start the application")
  public void theApplicationStartsWithTheDeclarations() {

    assertNotNull(workflowService, "the application booted with both values declared");

  }

}
