package io.vanillabp.integration.workflowtask;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;

/**
 * The Spring implementation of the core's {@link TransactionRunner} used to run
 * <code>&#64;WorkflowTask</code> handlers: {@link TransactionTemplate} with
 * propagation REQUIRES_NEW respectively MANDATORY. The
 * {@link PlatformTransactionManager} is resolved lazily - an application without
 * transactional persistence can still boot and gets a guiding message when the
 * first task is processed.
 */
public class SpringTransactionRunner implements TransactionRunner {

  private final ObjectProvider<PlatformTransactionManager> transactionManager;

  public SpringTransactionRunner(
      final ObjectProvider<PlatformTransactionManager> transactionManager) {

    this.transactionManager = transactionManager;

  }

  @Override
  public <T> T requireNew(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_REQUIRES_NEW);

  }

  @Override
  public <T> T inCurrent(
      final Supplier<T> work) {

    return run(work, TransactionDefinition.PROPAGATION_MANDATORY);

  }

  private <T> T run(
      final Supplier<T> work,
      final int propagation) {

    final var manager = transactionManager.getIfUnique();
    if (manager == null) {
      throw new IllegalStateException(
          """
              No (unique) PlatformTransactionManager is available to process a BPMN task! \
              @WorkflowTask methods load and save the workflow aggregate within a transaction. \
              Add a transactional persistence (e.g. spring-boot-starter-data-jpa with a data \
              source) or define a PlatformTransactionManager bean.""");
    }
    final var transactionTemplate = new TransactionTemplate(manager);
    transactionTemplate.setPropagationBehavior(propagation);
    return transactionTemplate.execute(status -> work.get());

  }

}
