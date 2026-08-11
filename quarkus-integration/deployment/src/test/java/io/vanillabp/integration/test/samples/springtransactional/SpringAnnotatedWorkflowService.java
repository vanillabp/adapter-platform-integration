package io.vanillabp.integration.test.samples.springtransactional;

import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

/**
 * A handler carrying SPRING's transaction annotation on Quarkus. Without the extension
 * {@code quarkus-spring-tx} Quarkus starts no transaction for it, so the annotation
 * cannot break the {@code TaskException} contract and the application has to boot.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SpringAnnotatedWorkflowService implements AggregatePersistenceAware<Aggregate> {

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
