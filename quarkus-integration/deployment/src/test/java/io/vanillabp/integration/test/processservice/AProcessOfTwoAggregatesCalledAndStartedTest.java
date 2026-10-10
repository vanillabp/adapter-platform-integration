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
 * The same as {@link AProcessOfTwoAggregatesStartedAndCalledTest}, with the class which calls the
 * process added first. The message is the same.
 * <p>
 * The text is the one Spring Boot says while it starts, see {@link TwoAggregates}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessOfTwoAggregatesCalledAndStartedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(TwoAggregates.LoanAggregate.class)
          .addClass(TwoAggregates.CustomerAggregate.class)
          .addClass(TwoAggregates.CallsTheProcess.class)
          .addClass(TwoAggregates.StartsTheProcess.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> assertTheBuildWasRefused(
          throwable,
          asSecondary(TwoAggregates.CallsTheProcess.class, TwoAggregates.CustomerAggregate.class),
          asBpmnProcess(TwoAggregates.StartsTheProcess.class, TwoAggregates.LoanAggregate.class)));

  @Test
  @DisplayName("Started by one aggregate and called by another, the caller found first: the build ends")
  public void theBuildEnds() {
    // the assertion happens on the build exception (assertException above)
  }

}
