package io.vanillabp.integration.test.apptx;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Assigns the application's unit of work to every aggregate which implements
 * {@link AppTxStored}. One bean serves the whole workflow module.
 */
@ApplicationScoped
public class AppTxTransactions implements TransactionRunnerAware<AppTxStored> {

  @Inject
  AppTxTransactionRunner runner;

  @Override
  public Class<AppTxStored> getAggregateClass() {

    return AppTxStored.class;

  }

  @Override
  public TransactionRunner getTransactionRunner() {

    return runner;

  }

}
