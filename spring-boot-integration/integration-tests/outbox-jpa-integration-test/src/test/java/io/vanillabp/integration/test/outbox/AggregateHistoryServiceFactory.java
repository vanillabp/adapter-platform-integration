package io.vanillabp.integration.test.outbox;

import io.vanillabp.integration.extension.spi.service.AggregateServiceContext;
import io.vanillabp.integration.extension.spi.service.AggregateServiceFactory;

/**
 * Builds one {@link AggregateHistoryService} per workflow aggregate - the shape of every
 * extension which offers a service of its own, and the only way an extension reaches the
 * persistence VanillaBP resolved for an aggregate.
 */
public class AggregateHistoryServiceFactory implements AggregateServiceFactory<AggregateHistoryService> {

  @Override
  public Class<AggregateHistoryService> getServiceInterface() {

    return AggregateHistoryService.class;

  }

  @Override
  public AggregateHistoryService createService(
      final AggregateServiceContext context) {

    return new AggregateHistoryService<Object>() {

      @Override
      public String auditingIdOf(
          final Object workflowAggregate) {

        return context.getAuditingId(workflowAggregate);

      }

      @Override
      public Object asItWasAt(
          final Object workflowAggregateId,
          final String auditingId) {

        return context.loadWorkflowAggregate(workflowAggregateId, auditingId);

      }

    };

  }

}
