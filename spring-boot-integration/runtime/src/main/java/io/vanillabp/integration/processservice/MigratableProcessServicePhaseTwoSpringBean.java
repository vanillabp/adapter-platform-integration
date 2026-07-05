package io.vanillabp.integration.processservice;

import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.spi.MigratableProcessServicePhaseTwo;
import io.vanillabp.integration.outbox.AggregateIdConverter;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.spi.process.ProcessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The Spring Boot implementation of {@link MigratableProcessServicePhaseTwo}: calls
 * scheduled through a {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} are
 * dispatched to this bean which routes them to the {@link ProcessServiceSpringBean}
 * responsible for the workflow module and BPMN process given.
 * <p>
 * Since outbox implementations may serialize the workflow aggregate's ID as a string
 * (e.g. the gruelbox-based implementation, whose invocation serializer only supports a
 * whitelist of types), the ID is converted back to the aggregate's ID type (determined
 * via {@link SpringDataUtil}) before calling the adapter.
 */
@RequiredArgsConstructor
@Slf4j
public class MigratableProcessServicePhaseTwoSpringBean implements MigratableProcessServicePhaseTwo {

  @SuppressWarnings("rawtypes")
  private final ObjectProvider<ProcessService> processServices;

  private final ObjectProvider<SpringDataUtil> springDataUtil;

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId,
      final Object workflowAggregateId) {

    final var processService = processServices
        .stream()
        .filter(ProcessServiceSpringBean.class::isInstance)
        .map(service -> (ProcessServiceSpringBean<?>) service)
        .filter(service -> service.getWorkflowModuleId().equals(workflowModuleId))
        .filter(service -> service
            .getMigrationProcessService()
            .getBpmnProcessId()
            .equals(bpmnProcessId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            ("No ProcessService found for BPMN process '%s' of workflow module '%s'! "
                + "Maybe it was available in a previous version of your software?")
                .formatted(bpmnProcessId, workflowModuleId)));

    final var aggregateId = convertAggregateId(
        workflowAggregateId,
        processService.getMigrationProcessService().getWorkflowAggregateClass());

    processService.startWorkflowPhaseTwo(adapterId, aggregateId);

  }

  /**
   * Converts a serialized (string) aggregate ID back to the aggregate's ID type. IDs
   * already having their original type are passed through unchanged.
   *
   * @param workflowAggregateId The aggregate ID (possibly serialized as a string)
   * @param workflowAggregateClass The aggregate's class used to determine the ID type
   * @return The aggregate ID to be passed to the adapter
   */
  private Object convertAggregateId(
      final Object workflowAggregateId,
      final Class<?> workflowAggregateClass) {

    if (!(workflowAggregateId instanceof String)) {
      return workflowAggregateId;
    }
    final var dataUtil = springDataUtil.getIfAvailable();
    if (dataUtil == null) {
      return workflowAggregateId;
    }
    return AggregateIdConverter.convert(
        workflowAggregateId,
        dataUtil.getIdType(workflowAggregateClass));

  }

}
