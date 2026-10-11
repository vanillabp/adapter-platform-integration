package io.vanillabp.integration.test.samples.transactional;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * A workflow service whose handler is annotated like an ordinary transactional service
 * method. The annotation joins the transaction which VanillaBP runs the handler in. So a
 * {@code TaskException} would throw away every change to the workflow aggregate. The
 * application must not boot with it.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class TransactionalWorkflowService implements AggregatePersistenceAware<Aggregate> {

  @Override
  public Class<Aggregate> getAggregateClass() {
    return Aggregate.class;
  }

  @Override
  public Aggregate save(
      final Aggregate aggregate) {
    return aggregate;
  }

  @Override
  public Object getAggregateId(
      final Aggregate aggregate) {
    return null;
  }

  @WorkflowTask
  @Transactional
  public void assessRisk(
      final Aggregate aggregate) {
  }

}
