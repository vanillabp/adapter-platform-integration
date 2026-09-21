package io.vanillabp.integration.outbox.gruelbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Ends the boot of an application which asked for the gruelbox store without having
 * gruelbox on its classpath.
 * <p>
 * VanillaBP stopped bringing that library along when it stopped being the default, so
 * <code>vanillabp.outbox.gruelbox.enabled</code> alone leaves an application with no
 * outbox at all. What it would read otherwise is the message about a workflow aggregate
 * which has no store, and that message names everything except the one thing which is
 * missing here.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "vanillabp.outbox.gruelbox.enabled", havingValue = "true")
@ConditionalOnMissingClass("com.gruelbox.transactionoutbox.TransactionOutbox")
public class GruelboxMissingAutoConfiguration {

  public GruelboxMissingAutoConfiguration() {

    throw new IllegalStateException(
        """
            'vanillabp.outbox.gruelbox.enabled' is 'true', but gruelbox is not on the classpath! \
            VanillaBP writes its own phase-two outbox since release 2.0 and does not bring that \
            library along any more. Either
            - add the dependencies 'com.gruelbox:transactionoutbox-core' and \
            'com.gruelbox:transactionoutbox-spring' to your application, or
            - remove 'vanillabp.outbox.gruelbox.enabled' and let VanillaBP store the entries in \
            its own table 'VANILLABP_PHASE_TWO_OUTBOX'. Entries which are still waiting in \
            gruelbox' table are reported at startup, so dispatch them with your previous version \
            before you switch.""");

  }

}
