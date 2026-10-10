package io.vanillabp.integration.test.processservice;

import static io.vanillabp.integration.test.samples.twoaggregates.TwoAggregates.asBpmnProcess;
import static io.vanillabp.integration.test.samples.twoaggregates.TwoAggregates.asSecondary;
import static io.vanillabp.integration.test.samples.twoaggregates.TwoAggregates.assertTheBuildWasRefused;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.samples.twoaggregates.TwoAggregates;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * One class starts a BPMN process, a class of another workflow aggregate calls it. The build ends,
 * naming both classes, both aggregates and the way each declares the process. The class which
 * starts the process is added first, {@link AProcessOfTwoAggregatesCalledAndStartedTest} adds
 * the other one first.
 * <p>
 * The text is the one Spring Boot says while it starts, see {@link TwoAggregates}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessOfTwoAggregatesStartedAndCalledTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(TwoAggregates.LoanAggregate.class)
          .addClass(TwoAggregates.CustomerAggregate.class)
          .addClass(TwoAggregates.StartsTheProcess.class)
          .addClass(TwoAggregates.CallsTheProcess.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> assertTheBuildWasRefused(
          throwable,
          asBpmnProcess(TwoAggregates.StartsTheProcess.class, TwoAggregates.LoanAggregate.class),
          asSecondary(TwoAggregates.CallsTheProcess.class, TwoAggregates.CustomerAggregate.class)));

  @Test
  @DisplayName("Started by one aggregate and called by another, the starter found first: the build ends")
  public void theBuildEnds() {
    // the assertion happens on the build exception (assertException above)
  }

}
