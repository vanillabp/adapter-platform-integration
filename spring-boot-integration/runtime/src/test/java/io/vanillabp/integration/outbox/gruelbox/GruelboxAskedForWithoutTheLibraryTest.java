package io.vanillabp.integration.outbox.gruelbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which still configures the gruelbox store although nothing on its
 * classpath can build one. The store left this repository, so the key alone would leave
 * such an application with the outbox VanillaBP writes itself and with its old entries
 * waiting in a table nobody reads.
 * <p>
 * The library is not on the classpath of this module, which is what makes the condition of
 * {@link GruelboxMissingAutoConfiguration} hold here without anything being hidden from the
 * class loader. That an application which HAS the artifact is left alone is proven where
 * that artifact is built.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxAskedForWithoutTheLibraryTest {

  private static ApplicationContextRunner applicationWithoutGruelbox() {

    return new ApplicationContextRunner()
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

  /**
   * What the boot has to say, with the key and the artifact put in from the constants the
   * configuration reads. A rename there has to reach the message, and the way back has to
   * name the table VanillaBP's own store writes, because that is where the entries of an
   * application which drops the key go.
   *
   * @return The message expected
   */
  private static String theMessageExpected() {

    return """
        '%s' is set, but no gruelbox store is on the classpath! That store is not part of VanillaBP any more, it lives in the artifact 'io.vanillabp:gruelbox-phase-two-outbox-spring-boot'. Either
        - add 'io.vanillabp:gruelbox-phase-two-outbox-spring-boot' to your application and keep the configuration as it is, or
        - remove '%s' and let VanillaBP store the entries in its own table 'VANILLABP_PHASE_TWO_OUTBOX'. Entries which are still waiting in gruelbox' table are reported at startup, so dispatch them with your previous version before you switch."""
        .formatted(
            GruelboxMissingAutoConfiguration.ENABLED_KEY,
            GruelboxMissingAutoConfiguration.ENABLED_KEY);

  }

  @Test
  @DisplayName("The boot ends naming the key, the artifact which carries the store and the way back")
  public void theBootEndsNamingWhatIsMissing() {

    applicationWithoutGruelbox()
        .withPropertyValues("%s=true".formatted(GruelboxMissingAutoConfiguration.ENABLED_KEY))
        .run(context -> assertEquals(theMessageExpected(), bootFailureOf(context)));

  }

  @Test
  @DisplayName("A value which is no boolean is answered the same way")
  public void aValueWhichIsNoBooleanIsAnsweredTheSameWay() {

    // 'yes' switches nothing on anywhere, so an application writing it would run the other
    // store without being told. The condition therefore reads the key rather than its value
    applicationWithoutGruelbox()
        .withPropertyValues("%s=yes".formatted(GruelboxMissingAutoConfiguration.ENABLED_KEY))
        .run(context -> assertEquals(theMessageExpected(), bootFailureOf(context)));

  }

  @Test
  @DisplayName("An application which switched the store off boots")
  public void anApplicationWhichSwitchedItOffBoots() {

    applicationWithoutGruelbox()
        .withPropertyValues("%s=false".formatted(GruelboxMissingAutoConfiguration.ENABLED_KEY))
        .run(context -> assertNull(
            context.getStartupFailure(),
            "a store which was switched off is not missing"));

  }

  @Test
  @DisplayName("An application which asks for nothing boots")
  public void anApplicationWhichAsksForNothingBoots() {

    applicationWithoutGruelbox()
        .run(context -> assertNull(
            context.getStartupFailure(),
            "the store nobody asked for must not be missed"));

  }

}
