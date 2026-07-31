package io.vanillabp.integration.processservice;

import java.util.List;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.outbox.AggregateIdConverter;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.SpringDataUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessServiceSpringBean<A> extends ProcessServiceBase<A> {

  @Getter
  private final MigrationProcessService<A> migrationProcessService;

  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final ObjectProvider<PhaseTwoOutbox> phaseTwoOutboxProvider,
      final PhaseTwoRouter phaseTwoRouter,
      final ObjectProvider<SpringDataUtil> springDataUtilProvider) {

    migrationProcessService = new MigrationProcessService<A>(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceAware, migratableProcessServices, buildLazyPhaseTwoOutbox(
            workflowModuleId, bpmnProcessId, phaseTwoOutboxProvider));

    // startup check: the aggregate's ID has to round-trip losslessly through the
    // outbox's String serialization (fails with a guiding message otherwise)
    validateAggregateIdRoundTrip(workflowAggregateClass, springDataUtilProvider);

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter != null) {
      phaseTwoRouter.register(
          migrationProcessService,
          buildAggregateIdConverter(workflowAggregateClass, springDataUtilProvider));
    }

  }

  /**
   * Validates AT STARTUP that the aggregate's ID type converts String → ID → String
   * losslessly: the ID crosses the phase-two outbox serialized as a String, so an
   * unconvertible type would corrupt the dispatch silently. If the ID type cannot
   * be determined (no Spring Data, custom persistence), the serialized form is the
   * custom layer's responsibility and nothing is validated.
   *
   * @param workflowAggregateClass The aggregate's class
   * @param springDataUtilProvider Provider of the persistence utility (may be empty)
   * @throws IllegalStateException If the ID type is known but not convertible,
   *           naming the aggregate class and the remedy
   */
  private static void validateAggregateIdRoundTrip(
      final Class<?> workflowAggregateClass,
      final ObjectProvider<SpringDataUtil> springDataUtilProvider) {

    final var springDataUtil = springDataUtilProvider == null
        ? null
        : springDataUtilProvider.getIfAvailable();
    if (springDataUtil == null) {
      return;
    }
    final Class<?> aggregateIdType;
    try {
      aggregateIdType = springDataUtil.getIdType(workflowAggregateClass);
    } catch (Exception e) {
      // not managed by Spring Data - the custom persistence layer owns the
      // serialized form
      return;
    }
    if (aggregateIdType == null) {
      return;
    }
    final var conversionService = org.springframework.core.convert.support.DefaultConversionService
        .getSharedInstance();
    if (!conversionService.canConvert(String.class, aggregateIdType) || !conversionService.canConvert(aggregateIdType,
        String.class)) {
      throw new IllegalStateException(
          """
              The ID of workflow aggregate '%s' is of type '%s', which cannot be converted from/to \
              String! The ID crosses the phase-two outbox serialized as a String and must round-trip \
              losslessly. Use a simple ID type convertible from/to String (e.g. String, Long, Integer, \
              UUID) or provide a custom AggregatePersistenceAware implementation handling the \
              serialized form."""
              .formatted(workflowAggregateClass.getName(), aggregateIdType.getName()));
    }

  }

  /**
   * Builds the converter turning the serialized (String) workflow-aggregate ID of an
   * outbox entry back into the aggregate's ID type. The ID type is determined via
   * Spring Data; if the aggregate is not a Spring-Data entity (i.e. a custom
   * {@link AggregatePersistenceAware} implementation is used), the String is passed
   * through unchanged instead of failing - the custom persistence layer is
   * responsible for handling the serialized form.
   *
   * @param workflowAggregateClass The aggregate's class used to determine the ID type
   * @param springDataUtilProvider Provider of the persistence utility (may be empty)
   * @return The converter registered with the {@link PhaseTwoRouter}
   */
  private static <A> Function<String, Object> buildAggregateIdConverter(
      final Class<A> workflowAggregateClass,
      final ObjectProvider<SpringDataUtil> springDataUtilProvider) {

    return serializedAggregateId -> {
      final var springDataUtil = springDataUtilProvider == null
          ? null
          : springDataUtilProvider.getIfAvailable();
      if (springDataUtil == null) {
        return serializedAggregateId;
      }
      final Class<?> aggregateIdType;
      try {
        aggregateIdType = springDataUtil.getIdType(workflowAggregateClass);
      } catch (Exception e) {
        log.debug(
            "Aggregate '{}' is not managed by Spring Data - passing the serialized aggregate ID through unchanged",
            workflowAggregateClass.getName(),
            e);
        return serializedAggregateId;
      }
      return AggregateIdConverter.convert(serializedAggregateId, aggregateIdType);
    };

  }

  /**
   * Builds a {@link PhaseTwoOutbox} resolving the actual outbox bean lazily on first
   * use: the outbox bean (and with it e.g. the DataSource) is not materialized when
   * the process service bean is created but only if an adapter actually requires a
   * two-phase commit for starting workflows.
   *
   * @param workflowModuleId The ID of the workflow module (used for error messages)
   * @param bpmnProcessId The BPMN process ID (used for error messages)
   * @param phaseTwoOutboxProvider The provider used to resolve the outbox bean
   * @return The lazily resolving outbox or <code>null</code> if no provider was given
   */
  private static PhaseTwoOutbox buildLazyPhaseTwoOutbox(
      final String workflowModuleId,
      final String bpmnProcessId,
      final ObjectProvider<PhaseTwoOutbox> phaseTwoOutboxProvider) {

    if (phaseTwoOutboxProvider == null) {
      return null;
    }
    return call -> {
      final var phaseTwoOutbox = phaseTwoOutboxProvider.getIfAvailable();
      if (phaseTwoOutbox == null) {
        throw new IllegalStateException(
            """
                Starting workflows of BPMN process '%s' of workflow module '%s' requires a two-phase commit, \
                but no PhaseTwoOutbox bean is available! To solve this either
                - add spring-boot-starter-data-jpa and configure a data source (enables the gruelbox-based default),
                - add spring-boot-starter-data-mongodb and configure the MongoDB connection (enables the MongoDB default), or
                - define your own bean implementing io.vanillabp.integration.adapter.spi.PhaseTwoOutbox."""
                .formatted(bpmnProcessId, workflowModuleId));
      }
      return phaseTwoOutbox.schedule(call);
    };

  }

  /**
   * Stops the process service. Called by
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService} on
   * graceful shutdown of the application.
   */
  public void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public String getWorkflowModuleId() {

    return migrationProcessService.getWorkflowModuleId();

  }

  public String getBpmnProcessId() {

    return migrationProcessService.getBpmnProcessId();

  }

  public Class<A> getWorkflowAggregateClass() {

    return migrationProcessService.getWorkflowAggregateClass();

  }

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (migrationProcessService.needsTwoPhaseCommitForStartingWorkflows() && noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  private boolean noTransactionIsActive() {

    return !TransactionSynchronizationManager.isActualTransactionActive();

  }

}
