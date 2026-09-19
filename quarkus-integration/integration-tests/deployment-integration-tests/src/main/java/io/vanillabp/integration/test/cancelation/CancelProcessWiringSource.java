package io.vanillabp.integration.test.cancelation;

import java.util.Collection;
import java.util.List;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the BPMN model of the derived-cancellation tests: the tasks of
 * 'CancelProcess' matching the handlers of {@link CancelWorkflowService}.
 */
@ApplicationScoped
public class CancelProcessWiringSource implements DummyTaskWiringSource {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return "CancelProcess".equals(bpmnProcessId)
        ? List.of(
            new BpmnTaskSpec("Activity_Signature", "awaitSignature"),
            new BpmnTaskSpec("Activity_Approval", "awaitApproval"),
            new BpmnTaskSpec("Activity_Payment", "awaitPayment"),
            new BpmnTaskSpec("Activity_Delivery", "awaitDelivery"))
        : List.of();

  }

}
