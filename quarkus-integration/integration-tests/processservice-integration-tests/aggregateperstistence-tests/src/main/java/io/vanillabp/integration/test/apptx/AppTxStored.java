package io.vanillabp.integration.test.apptx;

/**
 * The interface the workflow aggregates of this application implement, so ONE
 * {@link io.vanillabp.integration.spi.TransactionRunnerAware} bean naming it serves them
 * all.
 */
public interface AppTxStored {

  String getId();

}
