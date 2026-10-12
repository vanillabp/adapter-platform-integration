package io.vanillabp.integration.test.apptx;

/**
 * The workflow aggregates of this application implement this interface. So ONE
 * {@link io.vanillabp.integration.spi.TransactionRunnerAware} bean which names it serves
 * them all.
 */
public interface AppTxStored {

  String getId();

}
