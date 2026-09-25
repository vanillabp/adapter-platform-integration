package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the acceptance test of BPMS-initiated starts. Its process has
 * two start events the BPMS fires on its own, and it serves both: a process which can be
 * started by the BPMS and has no method for one of its start events does not let the
 * application boot.
 * <p>
 * The two methods also show the two ways an aggregate gets its id. The timer takes the
 * trigger time, which is what makes a repeated notification harmless; the signal has no
 * such value and builds one from what the model set.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = StartAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "StartProcess"))
public class StartWorkflowService {

  /**
   * The same workflow service also wants to know when a workflow ended.
   */
  @WorkflowEnded
  public void workflowEnded(
      final StartAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setStartedBy("ended:%s/%s".formatted(end.kind(), end.endEventId()));

  }

  @WorkflowStartedByBpms(id = "DailyTimer")
  public StartAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger,
      @TaskParam("startedAt") final String startedAt,
      @TaskParam("region") final String region,
      // a wrapper rather than the primitive: a notification which carries no amount
      // would end the start instead of building the aggregate
      @TaskParam("amount") final Integer amount) {

    final var aggregate = new StartAggregate();
    // the time is the application's own value, filled by an expression in the model and
    // read here - the trigger brings none, because no BPMS hands a start listener the
    // time it scheduled the start for
    aggregate.setId(startedAt);
    aggregate.setStartedBy(trigger.kind().name());
    aggregate.setRegion(region);
    aggregate.setAmount(amount == null
        ? 0
        : amount);
    return aggregate;

  }

  @WorkflowStartedByBpms(id = "SignalStart")
  public StartAggregate aggregateOfSignalStart(
      final BpmsStartTrigger trigger,
      @TaskParam("region") final String region) {

    final var aggregate = new StartAggregate();
    aggregate.setId("signal-"
        + region);
    aggregate.setStartedBy("%s/%s".formatted(trigger.kind(), trigger.signalName()));
    aggregate.setRegion(region == null
        ? null
        : region.toUpperCase());
    return aggregate;

  }

}
