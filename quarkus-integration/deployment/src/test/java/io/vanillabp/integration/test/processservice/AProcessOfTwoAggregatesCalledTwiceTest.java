package io.vanillabp.integration.test.processservice;

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
 * Classes of two workflow aggregates both list one BPMN process in their
 * {@code secondaryBpmnProcesses}. The build ends.
 * <p>
 * The text is the one Spring Boot says while it starts, see {@link TwoAggregates}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessOfTwoAggregatesCalledTwiceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(TwoAggregates.LoanAggregate.class)
          .addClass(TwoAggregates.CustomerAggregate.class)
          .addClass(TwoAggregates.AlsoCallsTheProcess.class)
          .addClass(TwoAggregates.CallsTheProcess.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> assertTheBuildWasRefused(
          throwable,
          asSecondary(TwoAggregates.AlsoCallsTheProcess.class, TwoAggregates.LoanAggregate.class),
          asSecondary(TwoAggregates.CallsTheProcess.class, TwoAggregates.CustomerAggregate.class)));

  @Test
  @DisplayName("Called by two aggregates: the build ends")
  public void theBuildEnds() {
    // the assertion happens on the build exception (assertException above)
  }

}
