package io.vanillabp.integration.test.apptx;

import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Attributes the application's unit of work to every aggregate implementing
 * {@link AppTxStored} - one bean for the whole workflow module (story 70).
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
