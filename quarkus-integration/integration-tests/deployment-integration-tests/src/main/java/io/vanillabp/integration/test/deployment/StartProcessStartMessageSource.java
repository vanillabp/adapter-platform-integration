package io.vanillabp.integration.test.deployment;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the acceptance test of a start by message:
 * 'StartProcess' starts on the message 'OrderPlaced', next to the timer and the signal start
 * event its workflow service serves. Every other process of the module has none of them.
 */
@ApplicationScoped
public class StartProcessStartMessageSource implements DummyBpmsInitiatedStartSource {

  @Override
  public Collection<BpmsInitiatedStartSpec> startEventsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "StartProcess".equals(bpmnProcessId)
        ? List.of(
            BpmsInitiatedStartSpec.of("DailyTimer", BpmsStartTrigger.Kind.TIMER),
            new BpmsInitiatedStartSpec("SignalStart", BpmsStartTrigger.Kind.SIGNAL, "OrderReceived"),
            BpmsInitiatedStartSpec.of("ReportingStart", BpmsStartTrigger.Kind.CONDITIONAL))
        : List.of();

  }

  @Override
  public Collection<String> startMessagesOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "StartProcess".equals(bpmnProcessId)
        ? List.of("OrderPlaced")
        : List.of();

  }

}
