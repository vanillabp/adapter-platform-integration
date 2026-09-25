package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.config.GruelboxOutboxProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which asks for the gruelbox store without having gruelbox. VanillaBP
 * stopped bringing that library along when it stopped being the default, so the property
 * alone leaves the application with no outbox at all, and the message it would read then
 * is about a workflow aggregate rather than about the library which is missing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxAskedForWithoutTheLibraryTest {

  private static ApplicationContextRunner applicationWithoutGruelbox() {

    return new ApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader(TransactionOutbox.class))
        .withConfiguration(AutoConfigurations.of(GruelboxMissingAutoConfiguration.class));

  }

  private static String bootFailureOf(
      final AssertableApplicationContext context) {

    final var failure = context.getStartupFailure();
    assertNotNull(failure, "the boot has to end with a guiding message");
    var cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

  @Test
  @DisplayName("The boot ends naming the property, the two dependencies and the way back")
  public void theBootEndsNamingWhatIsMissing() {

    applicationWithoutGruelbox()
        .withPropertyValues("%s=true".formatted(GruelboxOutboxProperties.ENABLED))
        .run(context -> {

          // the key comes from the constant, not from the text of this test: a rename
          // there has to reach the message, and the way back has to name the table the
          // default writes into, because that is where the entries of an application
          // which stops opting in go
          assertEquals(
              """
                  '%s' is 'true', but gruelbox is not on the classpath! VanillaBP writes its own phase-two outbox since release 2.0 and does not bring that library along any more. Either
                  - add the dependencies 'com.gruelbox:transactionoutbox-core' and 'com.gruelbox:transactionoutbox-spring' to your application, or
                  - remove '%s' and let VanillaBP store the entries in its own table 'VANILLABP_PHASE_TWO_OUTBOX'. Entries which are still waiting in gruelbox' table are reported at startup, so dispatch them with your previous version before you switch."""
                  .formatted(GruelboxOutboxProperties.ENABLED, GruelboxOutboxProperties.ENABLED),
              bootFailureOf(context));

        });

  }

  @Test
  @DisplayName("An application which asks for nothing boots without the library")
  public void anApplicationWhichAsksForNothingBoots() {

    applicationWithoutGruelbox()
        .run(context -> assertNull(
            context.getStartupFailure(),
            "the store nobody asked for must not be missed"));

  }

}
