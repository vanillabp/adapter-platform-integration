package io.vanillabp.integration.test.deployment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * The workflow service of the transaction-contract acceptance test. The handlers which
 * call the nested transactional bean have no transaction annotation themselves. The
 * startup check cannot cover this case, so the runtime check has to catch it.
 * <p>
 * {@link #acceptedAnnotation} has the annotation which a VanillaBP 1 application brings
 * along. Such an application has to boot and to work.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TransactionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TransactionProcess"))
public class TransactionWorkflowService {

  @Inject
  NestedTransactionalBean nestedTransactionalBean;

  /**
   * The handler lets the {@link TaskException} of the nested bean pass. So VanillaBP
   * would treat it as a BPMN error and commit the aggregate. But by then, the interceptor
   * of the nested bean has marked the transaction rollback-only.
   */
  @WorkflowTask
  public void nestedTaskException(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("about-to-fail");
    nestedTransactionalBean.raiseTaskException();

  }

  /**
   * The variant without any {@code TaskException}. The handler swallows the exception of
   * the nested call and returns normally. That would report the task as completed, while
   * nothing can be stored.
   */
  @WorkflowTask
  public void swallowedNestedFailure(
      final TransactionAggregate aggregate) {

    try {
      nestedTransactionalBean.fail();
    } catch (final RuntimeException e) {
      aggregate.setStatus("swallowed");
    }

  }

  @WorkflowTask
  @Transactional(dontRollbackOn = TaskException.class)
  public void acceptedAnnotation(
      final TransactionAggregate aggregate) {

    aggregate.setStatus("accepted");

  }

}
