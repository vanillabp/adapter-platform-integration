package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gruelbox.transactionoutbox.TransactionOutbox;

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
        .withPropertyValues("vanillabp.outbox.gruelbox.enabled=true")
        .run(context -> {

          final var message = bootFailureOf(context);

          assertTrue(message.contains("vanillabp.outbox.gruelbox.enabled"), message);
          assertTrue(message.contains("com.gruelbox:transactionoutbox-core"), message);
          assertTrue(message.contains("com.gruelbox:transactionoutbox-spring"), message);
          // the way back has to name the table the default writes into, because that is
          // where the entries of an application which stops opting in go
          assertTrue(message.contains("VANILLABP_PHASE_TWO_OUTBOX"), message);

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
