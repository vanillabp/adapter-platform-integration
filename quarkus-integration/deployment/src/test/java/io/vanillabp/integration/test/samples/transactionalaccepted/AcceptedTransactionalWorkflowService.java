package io.vanillabp.integration.test.samples.transactionalaccepted;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * The counterpart of {@code samples.transactional}: the annotation a VanillaBP 1
 * application carries. Excluding the {@link TaskException} from the rollback rules keeps
 * the contract intact, so the startup check accepts it and the application boots.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class AcceptedTransactionalWorkflowService implements AggregatePersistenceAware<Aggregate> {

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
  @Transactional(dontRollbackOn = TaskException.class)
  public void assessRisk(
      final Aggregate aggregate) {
  }

}
