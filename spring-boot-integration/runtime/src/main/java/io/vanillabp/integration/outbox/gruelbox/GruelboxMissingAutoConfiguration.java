package io.vanillabp.integration.outbox.gruelbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Ends the boot of an application which configures the gruelbox store although nothing on
 * its classpath can build one.
 * <p>
 * The store left this repository, so the keys below <code>vanillabp.outbox.gruelbox</code>
 * belong to the artifact which carries it now. An application which kept them and did not
 * add that artifact would get the outbox VanillaBP writes itself, silently, and its entries
 * would go into another table than the ones it still has waiting. What it would read
 * otherwise is the message about the table of the former store, and that message says
 * nothing about the configuration which asked for it.
 */
@AutoConfiguration
// every value but 'false' counts as asking for the store, a misspelled one included: a
// value Spring cannot read as a boolean would otherwise switch nothing on and say nothing
@ConditionalOnProperty(name = GruelboxMissingAutoConfiguration.ENABLED_KEY)
@ConditionalOnMissingClass(GruelboxMissingAutoConfiguration.THE_LIBRARY)
public class GruelboxMissingAutoConfiguration {

  /**
   * The key an application writes to ask for that store. It is written out here because
   * the class which binds it stands in the other repository, and
   * {@code GruelboxAskedForWithoutTheLibraryTest} names it as well and fails if the two
   * ever say something different.
   */
  public static final String ENABLED_KEY = "vanillabp.outbox.gruelbox.enabled";

  /**
   * The class every gruelbox-based store needs. It belongs to the library rather than to
   * VanillaBP, which is why this condition reads it and not one of our own classes: an
   * application has the library exactly where it has the artifact which uses it.
   */
  public static final String THE_LIBRARY = "com.gruelbox.transactionoutbox.TransactionOutbox";

  /**
   * The artifact carrying the store, named in the message so the remedy is one line to
   * copy.
   */
  private static final String THE_ARTIFACT = "io.vanillabp:gruelbox-phase-two-outbox-spring-boot";

  /**
   * Ends the boot. Spring builds this class only under the two conditions above, and a
   * configuration class which cannot be built stops the application context - which is the
   * whole point here: there is nothing to configure, only something to say.
   */
  public GruelboxMissingAutoConfiguration() {

    // the key comes from the constant the condition above reads, so a rename cannot leave
    // this message naming a key which is gone
    throw new IllegalStateException(
        """
            '%s' is set, but no gruelbox store is on the classpath! That store is not part of \
            VanillaBP any more, it lives in the artifact '%s'. Either
            - add '%s' to your application and keep the configuration as it is, or
            - remove '%s' and let VanillaBP store the entries in its own table \
            'VANILLABP_PHASE_TWO_OUTBOX'. Entries which are still waiting in gruelbox' table are \
            reported at startup, so dispatch them with your previous version before you switch."""
            .formatted(ENABLED_KEY, THE_ARTIFACT, THE_ARTIFACT, ENABLED_KEY));

  }

}
