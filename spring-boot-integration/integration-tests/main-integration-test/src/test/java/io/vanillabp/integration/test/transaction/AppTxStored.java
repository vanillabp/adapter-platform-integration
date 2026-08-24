package io.vanillabp.integration.test.transaction;

/**
 * The interface every workflow aggregate of this workflow module implements - the point
 * being that ONE {@link io.vanillabp.integration.spi.TransactionRunnerAware} bean naming
 * this interface serves them all.
 */
public interface AppTxStored {

  String getId();

}
