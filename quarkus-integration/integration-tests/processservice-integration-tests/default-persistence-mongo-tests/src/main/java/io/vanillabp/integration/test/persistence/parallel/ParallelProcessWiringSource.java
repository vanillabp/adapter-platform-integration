package io.vanillabp.integration.test.persistence.parallel;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN models of the parallel multi-instance test. Each process has the
 * tasks which its workflow service declares.
 */
@ApplicationScoped
public class ParallelProcessWiringSource implements DummyTaskWiringSource {

  private static final Set<String> PARALLEL_PROCESSES = Set
      .of("ParallelRepositoryProcess", "ParallelActiveRecordProcess");

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!PARALLEL_PROCESSES.contains(bpmnProcessId)) {
      return List.of();
    }
    final var iterations = List
        .of(
            new BpmnTaskSpec("Activity_Reading", "readingIteration"),
            new BpmnTaskSpec("Activity_Recording", "recordingIteration"),
            new BpmnTaskSpec("Activity_Writing", "writingIteration"));
    if (!"ParallelRepositoryProcess".equals(bpmnProcessId)) {
      return iterations;
    }
    final var singleTasks = List
        .of(
            new BpmnTaskSpec("Activity_ChangeRecord", "changeRecordBehindId"),
            new BpmnTaskSpec("Activity_PointElsewhere", "pointToAnotherRecord"),
            new BpmnTaskSpec("Activity_ChangeSubDocument", "changeSubDocument"));
    return java.util.stream.Stream
        .concat(iterations.stream(), singleTasks.stream())
        .toList();

  }

}
