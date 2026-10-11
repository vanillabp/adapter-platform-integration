package io.vanillabp.integration.test.transaction;

/**
 * The interface every workflow aggregate of this workflow module implements. The point is
 * that ONE {@link io.vanillabp.integration.spi.TransactionRunnerAware} bean which names this
 * interface serves them all.
 */
public interface AppTxStored {

  String getId();

}
