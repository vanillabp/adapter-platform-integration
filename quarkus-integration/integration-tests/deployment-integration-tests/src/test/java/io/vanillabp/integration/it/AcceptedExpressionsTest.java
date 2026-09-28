package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.logging.Level;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.it.ExpressionsInTheModelTest.ShippingAggregate;
import io.vanillabp.integration.it.ExpressionsInTheModelTest.ShippingAggregatePersistence;
import io.vanillabp.integration.it.ExpressionsInTheModelTest.ShippingWiringSource;
import io.vanillabp.integration.it.ExpressionsInTheModelTest.ShippingWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * The same application as in {@code ExpressionsInTheModelTest}, with the line accepting the
 * expressions written at its workflow. Nothing about them is said then, which is what the
 * line is for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AcceptedExpressionsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("expressions-accepted/application.yaml", "application.yaml")
          .addClass(ShippingAggregate.class)
          .addClass(ShippingAggregatePersistence.class)
          .addClass(ShippingWorkflowService.class)
          .addClass(ShippingWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/ShippingProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.INFO.intValue())
      .assertLogRecords(records -> {
        final var written = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        assertFalse(
            written.stream().anyMatch(message -> message.contains("more than the name of one variable")),
            written.toString());
        assertFalse(
            written.stream().anyMatch(message -> message.contains("compute instead of naming a variable")),
            written.toString());
      });

  @Inject
  ShippingWorkflowService workflowService;

  @Test
  @DisplayName("The accepted model starts without a word about its expressions")
  public void theAcceptedModelIsSilent() {

    assertNotNull(workflowService, "the application booted with the acceptance at its workflow");

  }

}
